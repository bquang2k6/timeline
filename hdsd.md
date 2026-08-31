1. Set env (PowerShell - mỗi lần mở terminal mới phải chạy lại)
set JAVA_HOME = "C:\Program Files\Java\jdk-17"
set ANDROID_HOME = "C:\Users\Quang\AppData\Local\Android\Sdk"
# Nếu có key CARTO (không bắt buộc nhưng không có sẽ không load được basemap khi render)
set CARTO_BASEMAP_API_KEY=

# fix PATH nếu `java -version` báo lỗi
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\emulator;" + $env:Path
java -version  # phải ra openjdk 17
Vĩnh viễn: Win+R > sysdm.cpl > Advanced > Environment Variables > thêm JAVA_HOME và ANDROID_HOME.

2. Build APK
Từ C:\coding\project\google-timeline-visualizer:

# Build nhanh nhất (debug, flavor github - giống release trên GitHub)
.\gradlew.bat assembleGithubDebug

# Các flavor khác (app/build.gradle.kts:58):
.\gradlew.bat assemblePlayDebug      # flavor Play Store
.\gradlew.bat assembleJournalLabDebug # flavor Lab (applicationId khác)

# Full check như CI (README.md:209)
.\gradlew.bat test lint assembleGithubDebug assemblePlayDebug
APK ra ở:

app\build\outputs\apk\github\debug\app-github-debug.apk
app\build\outputs\apk\play\debug\app-play-debug.apk
3. Chạy trên máy thật / Emulator
Bạn chưa có AVD nào, làm 1 trong 2 cách:

a) Máy thật (nhanh nhất): Bật Developer options > USB debugging trên điện thoại > cắm cáp > adb devices phải hiện device > chạy:

.\gradlew.bat installGithubDebug
b) Emulator: Mở Android Studio > More Actions > Virtual Device Manager > Create Device > chọn Pixel 8, API 36 > Start > rồi chạy installGithubDebug như trên. Hoặc bằng CLI:

avdmanager create avd -n Pixel36 -k "system-images;android-36;google_apis;x86_64"
emulator -avd Pixel36
c) Mở bằng Android Studio: File > Open > chọn C:\coding\project\google-timeline-visualizer > đợi sync > chọn githubDebug ở Build Variants > bấm Run.

Lưu ý
CARTO_BASEMAP_API_KEY rỗng vẫn build được, nhưng preview/video sẽ báo thiếu tile (app/build.gradle.kts:7). Official build inject key từ GitHub Actions.
Nếu lỗi SDK location not found thì tạo local.properties trong root:
sdk.dir=C\:\\Users\\Quang\\AppData\\Local\\Android\\Sdk