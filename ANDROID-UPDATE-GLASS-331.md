# Android update notice glass appearance / 2026-11-02 / task331

The newer-version dialog uses the same rounded translucent surface as code entry, a download icon, version chip, clear brand description and scrollable release notes. The update and later buttons use glass backgrounds and readable text, with a 48dp minimum height and a ripple on the primary action. Empty/null notes hide the notes section. Release notes render as plain text.

This changes the notice shown by the newly installed build; it cannot change the appearance of dialogs inside an already installed older APK. Native translucent gradients approximate glass, without a claim of real-time backdrop blur. No real-device screenshot is implied.

The higher-version and nonblank-download-address condition, check timing, network path and original browser ACTION_VIEW download remain unchanged. Later/back/outside dismiss remain possible. No silent overwrite install is added. Version/signing/public production metadata are untouched by this source change. The currently published Shenxian2.11.56/Wuai2.11.54 remains available and does not yet include this new dialog.

Editable: app/.../MainActivity.kt checkAppUpdate; design/layout/dialog_app_update.xml; drawable/bg_sxy_update_button.xml; values{,-zh}/strings.xml. Reuses the existing code-dialog surface and secondary action drawable. Original integration guards and resource parsing are local checks; Android binding/APK compilation and real-device large-font/landscape appearance remain CI/device gates. Rollback is a normal revert/rebuild, with prior stable APKs preserved.

Push and build submission are separate from release. After Actions dispatch stop and wait for the user completion notification before checking those new results.
