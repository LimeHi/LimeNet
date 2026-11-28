# LimeNet

Кроссплатформенный VPN-клиент для **Android** и **Windows** на Kotlin Multiplatform и Compose Multiplatform. Использует движки Xray и sing-box для работы с VPN-подключениями.

## Возможности

- Импорт VPN-конфигураций и подписок.
- Android VPN через системный VPN/TUN-интерфейс; приложение для Windows.
- Проверка доступности популярных сервисов и замер скорости через активное VPN-подключение.
- Управление профилями и подключением из общего интерфейса.

## Скачать

Актуальные установщики доступны на странице [Releases](https://github.com/LimeHi/LimeNet/releases). Ссылки на сборки Android и Windows также находятся в [`downloadlinks.txt`](downloadlinks.txt).

## Сборка из исходников

Нужны JDK 17, Gradle 9.3.1 и, для Android, установленный Android SDK. В репозитории нет Gradle Wrapper, поэтому команды запускаются установленным Gradle из корня проекта:

```powershell
gradle :composeApp:assembleRelease
gradle :composeApp:packageMsi
```

Release APK собирается в `composeApp/build/outputs/apk/release/`, MSI — в `composeApp/build/compose/binaries/main/msi/`.

Для подписанного Android APK задайте параметры подписи через переменные окружения (либо Gradle properties): `LIMENET_STORE_FILE`, `LIMENET_STORE_PASSWORD`, `LIMENET_KEY_ALIAS` и `LIMENET_KEY_PASSWORD`. Файл keystore и пароли не храните в репозитории.

## Сторонние компоненты и лицензии

Проект включает сторонние движки и данные в `vendor/`, Android-библиотеки в `composeApp/src/androidMain/jniLibs/` и ресурсы. Смотрите сопровождающие файлы `LICENSE` и `vendor/**/LICENSE` перед распространением сборок.

## Версия

Текущая версия исходников: **0.1.7** (код версии Android: **8**). Лицензионные условия проекта указаны в [`LICENSE`](LICENSE).