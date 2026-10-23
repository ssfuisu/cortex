# Tests on real phones (Firebase Test Lab)

`app/src/androidTest/java/org/cortex/terminal/UbuntuOnDeviceTest.kt` installs Ubuntu from the APK
on a real phone and runs commands in it as a terminal session would: bash as root, pipes and
signals, files, symlinks and hard links, and `apt-get install` from Ubuntu's archive. The emulator
test (`tests/e2e`) runs the arm64 Ubuntu through an x86_64 emulator's ARM translator, and this one
runs it on the ARM processors and Android kernels that users have.

CI runs it on [Firebase Test Lab](https://firebase.google.com/docs/test-lab) for pull requests and
dry runs (`.github/workflows/device-test.yml`), once the repository is set up for it. Without that
setup the job is skipped; the test APK is still built, so the test always compiles.

## Setting it up

1. Create a Firebase project (or use a Google Cloud project with Firebase added) at
   <https://console.firebase.google.com>. Test Lab's free Spark plan runs a few tests on physical
   phones a day; the Blaze plan bills per device minute.
2. In the Google Cloud console of that project, enable the **Cloud Testing API** and the **Cloud
   Tool Results API**.
3. Create a service account (IAM & Admin > Service Accounts) with the role **Editor**, and create
   a JSON key for it. If you would rather not grant Editor: give it **Firebase Test Lab Admin** and
   **Firebase Analytics Viewer**, create a Cloud Storage bucket for the results that it may write
   to, and set `FIREBASE_RESULTS_BUCKET` below (Test Lab's default bucket needs Editor).
4. In the GitHub repository (Settings > Secrets and variables > Actions):
   - secret `GCP_SA_KEY`: the whole JSON key file;
   - variable `FIREBASE_PROJECT_ID`: the project's ID;
   - optional variable `FIREBASE_DEVICES`: the phones, one `model=...,version=...` per line
     (`gcloud firebase test android models list` shows them; the default is a Pixel 6 with
     Android 13 and a Pixel 8 with Android 14);
   - optional variable `FIREBASE_RESULTS_BUCKET`: see step 3.

The job summary of each run shows the outcome per phone and links to Test Lab's results (logcat,
video, the test's output).

## Running it on your own phone

With a phone connected over adb and the bootstrap archives in `app/src/main/assets` (CI builds them,
see `.github/workflows/build-release.yml`):

    ./gradlew connectedDebugAndroidTest

The test uses the app's own data: an Ubuntu already installed there is updated, not replaced.
