# Zichan Market Monitor — Windows Desktop

A compact, always-on-top Windows price panel for Deriv public market ticks. It is designed to sit at the left or right edge of a PC monitor and show prices only—no charts or trading controls.

## Features
- Frameless, resizable, always-on-top desktop panel
- Starts near the right edge; drag it to either side
- Live Deriv ticks with reconnect/backoff
- Search Deriv's live active-symbol catalogue and add markets
- Opacity, price text scale, and always-on-top controls
- Compact dark Zichan styling; green/red tick direction
- No API token or account login required for public market data

## Build on Windows
1. Install Node.js 20 LTS or newer.
2. Open a terminal in the `desktop` folder.
3. Run `npm install`.
4. Run `npm run dist`.
5. Installer is written to `desktop/dist`.

GitHub Actions also builds a Windows installer when changes are pushed to this feature branch or when manually dispatched.

## Notes
The app uses Deriv's public WebSocket market feed. Instrument availability and symbol names are determined by Deriv's live `active_symbols` response. The starter watchlist contains Crash 50 and Gold / USD. More markets can be added in Settings.
