# Play Console app content

Use these answers as the submission baseline and verify them against the final
Play bundle before submitting.

## Basic declarations

- App category: **Video Players & Editors**
- Contains ads: **No**
- App access: **All functionality is available without an account or login**
- Target audience: **18 and over**
- News app: **No**
- Government app: **No**
- Financial features: **No**
- Health features: **No**
- Account creation: **No**; an account-deletion flow is not applicable

## Data safety

The Timeline file, Journey, user-entered name and title, Videos index,
thumbnails, and MP4 videos are processed only on the device. Do not declare those
items as collected solely because the app accesses them locally.

For a conservative declaration, include the following CARTO map-tile traffic:

| Data type | Handling | Purpose | Required? | Notes |
| --- | --- | --- | --- | --- |
| Precise location | Collected and shared | App functionality | Optional | Tile coordinates can identify areas smaller than 3 km². The user can cancel before loading. |
| Device or other identifiers | Collected and shared | App functionality, fraud prevention/security | Optional | Standard HTTPS requests expose an IP address and user agent to CARTO. |

- Data is encrypted in transit: **Yes**
- Users can request deletion from the developer: **No developer-operated server stores user data**
- Data is processed ephemerally: **Do not claim this unless CARTO confirms that the relevant request data is not retained beyond the real-time request**

The final answers must remain consistent with `docs/privacy.md`, the first-load
disclosure, and CARTO's current privacy practices.

## Content rating

Complete the IARC questionnaire accurately. The app contains no violence, sexual
content, gambling, controlled substances, user interaction, or unrestricted web
browsing. It opens only the privacy policy, project page, update destination, and
the phone's Location settings through explicit buttons.

## Foreground service and notifications

- Foreground service type: **Media processing** on Android 15 and newer;
  **Data sync** is the compatibility type on older Android versions for the same
  user-requested local file-processing task
- Purpose: encode the user-requested Timeline animation into an MP4 after the app
  is no longer visible or the screen is off
- User initiation: the service starts only after the user selects **Create video**.
  Android 10 and later create a pending MediaStore item automatically. Android 8
  and 9 use the system Save As picker.
- User awareness and control: the ongoing notification shows progress and a
  **Cancel** action; the completed notification offers **Watch** and **Share**
- `POST_NOTIFICATIONS`: requested on Android 13 and newer only to show progress
  and completion alerts in the notification drawer; video creation still works
  if the user declines

## Reviewer instructions

Timeline Visualizer does not require login. To test it, open **New video**, select **Choose file**,
accept or cancel the map privacy disclosure, and choose a compatible Timeline JSON.
The repository includes `test-fixtures/seoul-bohol-sample.json` as a synthetic test
file containing no real user's location history.
