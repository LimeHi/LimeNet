#include <jni.h>

#include <cerrno>
#include <cstdint>
#include <ctime>
#include <cstring>
#include <fcntl.h>
#include <signal.h>
#include <string>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#include <vector>

extern char** environ;

namespace {
constexpr jint kStillRunning = (-2147483647 - 1);
constexpr int kTunChildFd = 3;

std::string toString(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jint statusCode(int status) {
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_limeprod_limenet_NativeXray_launch(
    JNIEnv* env, jclass, jint tun_fd, jstring binary_value,
    jstring config_value, jstring assets_value, jstring log_value) {
    const std::string binary = toString(env, binary_value);
    const std::string config = toString(env, config_value);
    const std::string assets = toString(env, assets_value);
    const std::string log = toString(env, log_value);
    if (env->ExceptionCheck() || binary.empty() || config.empty() || log.empty()) return -EINVAL;

    std::vector<std::string> environment;
    for (char** item = environ; item != nullptr && *item != nullptr; ++item) {
        const char* entry = *item;
        if (strncmp(entry, "XRAY_TUN_FD=", 12) == 0 ||
            strncmp(entry, "XRAY_LOCATION_ASSET=", 20) == 0) continue;
        environment.emplace_back(entry);
    }
    if (tun_fd >= 0) environment.emplace_back("XRAY_TUN_FD=3");
    if (!assets.empty()) environment.emplace_back("XRAY_LOCATION_ASSET=" + assets);
    std::vector<char*> envp;
    envp.reserve(environment.size() + 1);
    for (std::string& entry : environment) envp.push_back(entry.data());
    envp.push_back(nullptr);

    std::vector<char*> argv{
        const_cast<char*>(binary.c_str()),
        const_cast<char*>("run"),
        const_cast<char*>("-c"),
        const_cast<char*>(config.c_str()),
        nullptr,
    };

    // Duplicate above FD 3 so dup2 always clears CLOEXEC, including when the
    // original TUN descriptor itself was allocated as FD 3.
    const int spawn_tun_fd = tun_fd >= 0 ? fcntl(tun_fd, F_DUPFD, 10) : -1;
    if (tun_fd >= 0 && spawn_tun_fd < 0) return -errno;

    // Use a CLOEXEC error pipe: EOF means execve succeeded; an errno means
    // the child reported a setup/exec failure. Child-side work is restricted
    // to async-signal-safe syscalls after fork.
    int error_pipe[2];
    if (pipe(error_pipe) != 0) {
        const int error = errno;
        if (spawn_tun_fd >= 0) close(spawn_tun_fd);
        return -error;
    }
    if (fcntl(error_pipe[1], F_SETFD, FD_CLOEXEC) < 0) {
        const int error = errno;
        close(error_pipe[0]);
        close(error_pipe[1]);
        if (spawn_tun_fd >= 0) close(spawn_tun_fd);
        return -error;
    }
    const pid_t pid = fork();
    if (pid < 0) {
        const int error = errno;
        close(error_pipe[0]);
        close(error_pipe[1]);
        if (spawn_tun_fd >= 0) close(spawn_tun_fd);
        return -error;
    }
    if (pid == 0) {
        close(error_pipe[0]);
        int child_error = 0;
        if (spawn_tun_fd >= 0 && dup2(spawn_tun_fd, kTunChildFd) < 0) child_error = errno;
        if (spawn_tun_fd >= 0) close(spawn_tun_fd);
        if (child_error == 0) {
            const int output = open(log.c_str(), O_WRONLY | O_CREAT | O_APPEND, 0600);
            if (output < 0) child_error = errno;
            else {
                if (dup2(output, STDOUT_FILENO) < 0 || dup2(output, STDERR_FILENO) < 0) child_error = errno;
                if (output != STDOUT_FILENO && output != STDERR_FILENO) close(output);
            }
        }
        if (child_error == 0) execve(binary.c_str(), argv.data(), envp.data());
        if (child_error == 0) child_error = errno;
        (void)write(error_pipe[1], &child_error, sizeof(child_error));
        _exit(127);
    }

    if (spawn_tun_fd >= 0) close(spawn_tun_fd);
    close(error_pipe[1]);
    int child_error = 0;
    ssize_t bytes;
    do {
        bytes = read(error_pipe[0], &child_error, sizeof(child_error));
    } while (bytes < 0 && errno == EINTR);
    close(error_pipe[0]);
    if (bytes < 0) {
        const int error = errno;
        kill(pid, SIGKILL);
        while (waitpid(pid, nullptr, 0) < 0 && errno == EINTR) {}
        return -error;
    }
    if (bytes > 0) {
        int status = 0;
        while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {}
        return -child_error;
    }
    return static_cast<jint>(pid);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_limeprod_limenet_NativeXray_waitFor(JNIEnv*, jclass, jint pid, jlong timeout_ms) {
    const bool infinite = timeout_ms < 0;
    timespec start{};
    clock_gettime(CLOCK_MONOTONIC, &start);
    const int64_t start_ms = static_cast<int64_t>(start.tv_sec) * 1000 + start.tv_nsec / 1000000;
    const int64_t deadline = start_ms + timeout_ms;
    for (;;) {
        int status = 0;
        const pid_t result = waitpid(static_cast<pid_t>(pid), &status, WNOHANG);
        if (result == pid) return statusCode(status);
        if (result < 0) {
            if (errno == EINTR) continue;
            return -errno;
        }
        if (!infinite) {
            timespec now{};
            clock_gettime(CLOCK_MONOTONIC, &now);
            const int64_t now_ms = static_cast<int64_t>(now.tv_sec) * 1000 + now.tv_nsec / 1000000;
            if (now_ms >= deadline) return kStillRunning;
        }
        usleep(10 * 1000);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_limeprod_limenet_NativeXray_terminate(JNIEnv*, jclass, jint pid, jboolean force) {
    const int signal = force ? SIGKILL : SIGTERM;
    return kill(static_cast<pid_t>(pid), signal) == 0 ? JNI_TRUE : JNI_FALSE;
}
