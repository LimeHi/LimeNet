cmake_minimum_required(VERSION 3.22.1)
project(limenet_tun_launcher LANGUAGES CXX)

add_library(tunlauncher SHARED tun_launcher.cpp)
target_compile_features(tunlauncher PRIVATE cxx_std_17)
