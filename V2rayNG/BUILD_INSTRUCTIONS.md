# Инструкция по сборке APK v2rayNG

## Необходимые файлы в app/libs/

Для сборки рабочего APK требуются два AAR-файла в директории `app/libs/`:

1. **libv2ray.aar** — основное ядро Xray/v2ray
   - Скачать: https://github.com/2dust/AndroidLibXrayLite/releases/latest
   - Файл: `libv2ray.aar`

2. **libhev-socks5-tunnel.aar** — библиотека для HevTUN режима
   - Уже включена в проект (создана локально)

## Процесс сборки новой версии

### 1. Переключиться на нужный тег

```bash
cd /home/hohla/git/v2rayNG/V2rayNG
git fetch upstream --tags
git checkout --force tags/X.X.X  # заменить X.X.X на нужную версию
```

### 2. Добавить подпис debug-ключом

В файле `app/build.gradle.kts` найти блок `buildTypes { release { ... } }` и добавить:

```kotlin
signingConfig = signingConfigs.getByName("debug")
```

Пример:
```kotlin
buildTypes {
    release {
        isMinifyEnabled = false
        signingConfig = signingConfigs.getByName("debug")  // <-- эта строка
        proguardFiles(...)
    }
}
```

### 3. Скопировать AAR-библиотеки в app/libs/

```bash
mkdir -p app/libs

# libv2ray.aar (скачать свежий)
curl -L "https://github.com/2dust/AndroidLibXrayLite/releases/latest/download/libv2ray.aar" -o app/libs/libv2ray.aar

# libhev-socks5-tunnel.aar (уже есть в репозитории, нужно скопировать из другой ветки или создать заново)
```

### 4. Собрать APK

```bash
./gradlew clean assembleRelease
```

APK будут в `app/build/outputs/apk/playstore/release/`

### 5. Установить на устройство

```bash
# Удалить старую версию (если есть конфликт подписей)
adb uninstall com.v2ray.ang

# Установить новую
adb install app/build/outputs/apk/playstore/release/v2rayNG_X.X.X_arm64-v8a.apk
```

## Создание libhev-socks5-tunnel.aar (если нужно пересоздать)

```bash
cd /tmp
mkdir -p hev-aar/jni/arm64-v8a
cp /path/to/libhev-socks5-tunnel.so hev-aar/jni/arm64-v8a/
cd hev-aar

cat > AndroidManifest.xml << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.hev.tunnel">
</manifest>
EOF

zip -r ../libhev-socks5-tunnel.aar jni/ AndroidManifest.xml
```

## Известные проблемы

### Ошибка: libhev-socks5-tunnel.so not found
Решение: убедиться, что `libhev-socks5-tunnel.aar` лежит в `app/libs/`

### Ошибка: INSTALL_FAILED_UPDATE_INCOMPATIBLE
Решение: удалить старую версию через `adb uninstall com.v2ray.ang`

### Ошибка: jar is unsigned
Решение: добавить `signingConfig = signingConfigs.getByName("debug")` в build.gradle.kts
