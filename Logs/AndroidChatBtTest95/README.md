# AndroidChatBtTest95 physical test notes

App installed: `com.taskertowpf.androidchatbttest95` (2026-09-02).

## How to verify
1. Force-stop AndroidChatCopyV1 / AndroidChat / AndroidChatBtTestV1 if needed.
2. Open AndroidChatBtTest95 → Settings → enable native headset capture → Connect if needed.
3. Open **Tests** screen (isolation mode starts HeadsetMonitorService).
4. Press physical Play on Pixel Buds.
5. Expect log: `HARDWARE: MEDIA_PLAY via hardware-...`
6. UI Simulate Play should log: `SIMULATED: MEDIA_PLAY via ui-simulate`

Physical button result: **not confirmed in this session** (needs manual headset press).
Export logs here after the test.
