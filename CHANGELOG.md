# Changelog

## 5.0.8

### Improved
- Movies, series, the TV guide, playlists and the device screen have a new look that matches the home screen, and move more smoothly with the remote.
- Movies and series: posters are shown five to a row, and the focused poster no longer covers its title.
- Live TV: the channel list, info bar, options and number entry over the picture have the new look.
- Adding a playlist happens on the screen itself instead of in a pop-up; typing starts when you press OK.

### Fixed
- Movie player: the remote's keys reach the player's controls.

## 5.0.7

### Improved
- Channel logos, playlists and updates use newer, more reliable network and image components.

### Fixed
- An update you download is shared only with Android's installer, nothing else the app keeps.

## 5.0.6

### New
- Live TV: channels with rtsp:// and rtmp:// addresses play.

### Fixed
- Live TV: what Faznet learned about channels (for example which decoder works best) is no longer all forgotten once the list of remembered channels gets full; only the channels not watched for longest make room.

## 5.0.5

### Fixed
- Playlists added as a link from providers whose address has an extra folder (such as /xc/get.php): movies, series, the TV guide, catch-up and "now / next" work now.
- Live TV loads faster for these providers.

## 5.0.4

### New
- Movies and series: a "Sound & subtitles" button in the player, to choose the subtitles (or none) and the sound language a film offers.
- The chosen subtitle and sound language is remembered for the next film or episode.
- Subtitle and sound languages are shown by their full name.

## 5.0.3

### New
- Movies and series: a category you already opened comes back at once, without loading again.

### Fixed
- Movies and series: typing in the search no longer gets slow with big lists.
- Live TV: "now / next" also shows for providers whose channel addresses have another form.

## 5.0.2

### Fixed
- Movies and series: big lists load with much less memory, so small TV sticks stay stable.
- Movies and series: an answer from the server that breaks off half way is shown as a server failure (OK tries again), not as a shorter list.
- Movies, series and the TV guide use the User-Agent chosen in Settings, as live TV does.
- Problem reports no longer contain server names.

## 5.0.1

### New
- A cleaner start screen: the Live TV, Movies and Series cards show only their name, in bigger, bolder letters.

## 5.0.0

### New
- A new look, in the style of Apple TV: a dark graphite design with white focus, on every screen.
- A new Faznet logo.
- A new start screen with three cards: Live TV, Movies and Series. Channels, movies and series load only when you choose them.
- Movies and series: posters in a grid, and a page for each series with its seasons and episodes. The poster under the remote stands out clearly.
- Series: the next episode starts by itself when one ends.
- Movies and series: a picture size button (Fit, Stretch, Zoom) next to the settings, remembered for every film. Zoom removes black bars inside the picture.
- Live TV holds live channels only, so big Xtream lists load much faster and use less memory.
- The player shows a floating info card and a clear channel list over the picture.
- A new TV guide, settings, playlists, device and internet test screens.

### Fixed
- Movies and series: when the server does not answer, Faznet says so and OK tries again (before it said "nothing here").
- Movies and series: going back from a series no longer moves the focus to the Back button.
- Movies and series continue by themselves when the internet comes back.
- Series on phones held upright: the episodes are visible again.
- The channel list, options or a half-typed channel number no longer stay on the screen after leaving the player and coming back.
- The TV guide's "now / next" stays right after a programme ends.
- Crash reports never contain stream addresses.
- Newer video engine (Media3 1.11).

## 4.3.5

### New
- The suggestion to restart the device is now a red message, in English and Albanian together, so it is easy to notice and understand.

## 4.3.4

### New
- When several channels freeze right at the start within a few minutes, Faznet suggests restarting the device. A restart clears this on TV sticks. Shown at most once a day, and it can be turned off.
- Phones: channels play upright. The new Full screen button, first in the player's top bar, turns the picture sideways, and Back turns it upright again.

### Fixed
- The player's top bar no longer runs off narrow screens.

## 4.3.3

### New
- Problem reports also include native crashes, "not responding" and being closed for memory on screen (Android 11 and newer, with automatic reports on).

## 4.3.2

### Fixed
- Full HD and HD channels stay on the device's own video decoder. The software decoder is used for SD channels only, and is left at once if it can't keep up.

## 4.3.1

### New
- Movies, series and the TV guide load large lists without freezing the screen. The guide shows the programme straight away.
- Movies, episodes and catch-up continue by themselves after a short internet drop.

### Fixed
- The sleep timer keeps running after the app was in the background.
- The software decoder for SD channels now really takes effect on TV sticks.
- Better protection of playlists uploaded from the web.
