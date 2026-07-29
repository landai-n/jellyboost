# Bugs founds
- Search page top texts are showing under the system icons, making them unreadable.
- Switching to offline mode still lists online media on the Home screen (should show only downloaded/available content).
- The app is probably configured as light theme even though it uses dark colors: system status-bar text and icons are drawn in black over the dark UI, making them unreadable.
- Downloading a season fails: queue rows for whole seasons (e.g. La Pat' Patrouille S5/S6, Pyjamasques S1) end in "Download failed: The server couldn't send this download (error 400)".
- Download speed is showing crazy numbers not matching the actual speed (like 100MB/s to 180MB/s for something more in the range of 2MB/s to 8MB/s).
- Download pausing doesn't work.

# Polishing
- The offline mode status bar is taking real estate on the screen, it should be a small icon instead of a bar.
- Media description/metadata is not always showing in the offline mode, it should be available for downloaded files.
- Media title is showing twice in the presentation, once in the header and once in the description.
- Media hero image is not correctly positioned, considering the ratio of the image, it should be centered and cropped to fit the screen.
- Downloads page shows movies under a category with the same name as the movie, showing the name twice
- The Download page "Wifi only" toggle is missing spacing between the text and the switch
- Deleting a downloaded file is not showing a confirmation dialog, it should ask for confirmation before deleting.
- The combined navbar and top bar are wasting space, combine them.
- Settings subcategories are shown like clickable items (Skip intro, Skip outro), making it confusing to know what they are about.
- Scrolling media lists is not smooth, it should be optimized for performance and smoothness.

# Next steps
- We should have a general quality setting for offline downloads, allowing users to choose the quality of the downloaded files, optimizing for storage space or quality.
