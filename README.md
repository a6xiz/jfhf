# ComfyPix

A light Android client for a ComfyUI server on your own network.

## Build the APK on GitHub

1. Create a new GitHub repository and upload everything in this folder (keep `.github/workflows/build.yml`).
2. Open the **Actions** tab and run **Build APK** (it also runs on every push to `main`).
3. When it finishes, open the run and download the **ComfyPix-debug-apk** artifact. Unzip it and install `app-debug.apk`.

No Gradle wrapper is included; the workflow installs Gradle 8.7 and JDK 17 itself.

## Connect to ComfyUI

Start ComfyUI so it accepts connections from other devices, for example `python main.py --listen 0.0.0.0`.
In the app open **Connect**, enter the computer's IP address and port (default 8188), tap **Test these settings**, then **Save settings**.

## Workflows

* **Flows → File / Paste / Server** imports a workflow.
  * "Save (API Format)" files are used as they are.
  * Normal saved workflows (the ones in ComfyUI's workflows folder) are converted using your server's node definitions, so connect first.
* The app works out which nodes are the positive/negative prompt, model loaders, size, sampler, input images and outputs, and shows the usual ones.
* The 🎚️ button on **Create** lets you switch any input or output on or off. Choices are saved per workflow.
* If a dropdown can't load its list, you can still type the value, and 🔄 reloads the lists.

## Notes

* Needs Android 10 or newer.
* The APK is a debug build signed with the debug key. That is fine for installing on your own phone.
* Passwords and tokens are stored in the app's private storage in plain form.
