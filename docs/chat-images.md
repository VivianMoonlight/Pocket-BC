# Chat images

The **Image** button in the chat composer opens host selection and the Android file picker. Uploading returns a direct URL; **Insert link** appends it to the existing draft. The normal Send action retains the current channel, reply and game-rule validation. Switching conversations, whisper targets or composer modes disposes the upload flow; a late response cannot insert into another draft.

Image URLs in messages, including history, have an explicit **Load image** button. No preview request is made before that action. Failed loads can be retried; loaded previews open a larger view. Supported URL suffixes are JPG/JPEG, PNG, GIF, WebP, BMP, AVIF and SVG, including query strings and parenthesized Liko links. Page URLs are not scraped for images.

| Host | Upload expiry | Credentials | App size limit |
| --- | --- | --- | --- |
| Litterbox | 12 hours | None | 20 MB |
| Catbox | No expiry requested | None | 20 MB |
| TmpFiles | 1 hour | None | 20 MB |
| ImgBB | No expiry requested | API Key | 20 MB |
| Imgur | No expiry requested | Client ID, anonymous upload | 10 MB |

Provider availability and retention remain controlled by the provider. The app accepts JPG, PNG, GIF, WebP and BMP files, checks decoded image bounds, and bounds the copied file even if its content provider reports an incorrect size. Uploads use cancellable HTTPS requests with a two-minute timeout and delete the local temporary file afterward. Credentials are encrypted with an Android Keystore key in the app's no-backup directory, and can be cleared from the upload dialog. There is no automatic fallback to another host.

Behavior reference: [Liko Image Uploader](https://github.com/awdrrawd/liko-Plugin-Repository/blob/main/Plugins/main/Liko%20-%20Image%20Uploader.main.user.js), particularly Litterbox upload fields, 12-hour expiry, draft insertion and expanded image viewing. This implementation uses the providers directly, without third-party proxy credentials.

Provider references: [Catbox](https://catbox.moe/tools.php), [Litterbox](https://litterbox.catbox.moe/tools.php), [TmpFiles](https://tmpfiles.org/api), [ImgBB](https://api.imgbb.com/), [Imgur upload](https://api.imgur.com/endpoints/image), [Imgur anonymous authorization](https://api.imgur.com/oauth2).
