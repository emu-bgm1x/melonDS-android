#ifndef MELONINSTANCE_H
#define MELONINSTANCE_H

#include <atomic>
#include <string>
#include "Args.h"
#include "Configuration.h"
#include "NDS.h"
#include "MelonDS.h"
#include "SaveManager.h"
#include "RewindManager.h"
#include "renderer/FrameQueue.h"
#include "renderer/Renderer.h"
#include "renderer/ScreenshotRenderer.h"
#include "retroachievements/RetroAchievementsManager.h"
#include "net/Net.h"
#include "bgm1x/BgmReplacer.h"
#include "bgm1x/SoundPreview.h"

using namespace melonDS;

namespace MelonDSAndroid
{

class MelonInstance : public BgmReplacerHost
{

public:
    MelonInstance(int instanceId, std::shared_ptr<EmulatorConfiguration> configuration, std::unique_ptr<melonDS::NDSArgs> args, std::shared_ptr<Net> net, std::unique_ptr<ScreenshotRenderer> screenshotRenderer, int consoleType);
    ~MelonInstance();

    int getInstanceId() { return instanceId; };

    bool loadRom(std::string romPath, std::string sramPath);
    bool loadGbaRom(std::string romPath, std::string sramPath);
    void loadRumblePak();
    void loadGbaMemoryExpansion();
    void loadMotionPakHomebrew();
    void loadMotionPakRetail();
    bool bootFirmware();
    void start();
    void reset();
    melonDS::u32 runFrame();
    void stop();

    void updateMotionData(float ax, float ay, float az, float rx, float ry, float rz);
    float getMotionData(MotionQueryType type);
    void touchScreen(u16 x, u16 y);
    void releaseScreen();
    void pressKey(u32 key);
    void releaseKey(u32 key);
    int readAudioOutput(s16* buffer, int length);
    void setAudioOutputSkew(double skew);
    bool takeScreenshot();
    void loadCheats(std::list<Cheat> cheats);
    int sendNetPacket(u8* data, int length);
    int receiveNetPacket(u8* data);

    Frame* getPresentationFrame(std::optional<std::chrono::time_point<std::chrono::steady_clock>> deadline);

    void updateConfiguration(std::shared_ptr<EmulatorConfiguration> newConfiguration);
    void requestNdsSaveWrite(const u8* saveData, u32 saveLength, u32 writeOffset, u32 writeLength);
    void requestGbaSaveWrite(const u8* saveData, u32 saveLength, u32 writeOffset, u32 writeLength);
    void requestFirmwareSaveWrite(const u8* saveData, u32 saveLength, u32 writeOffset, u32 writeLength);
    bool saveState(Savestate* state);
    bool loadState(Savestate* state);
    RewindWindow getRewindWindow();
    bool loadRewindState(RewindSaveState rewindSaveState);
    void setupAchievements(
        std::list<RetroAchievements::RAAchievement> achievements,
        std::list<RetroAchievements::RALeaderboard> leaderboards,
        std::optional<std::string> richPresenceScript
    );
    void unloadRetroAchievementsData();
    std::string getRichPresenceStatus();
    std::vector<RetroAchievements::RARuntimeAchievement> getRuntimeAchievements();

    // ff-bgm1x: 오디오 스레드가 출력 버퍼에 1배속 소리를 더한다
    void mixBgm(s16* buffer, int frames, int volume);
    // 에뮬레이션 스레드: 연출 중이라 배속을 멈춰야 하는지
    bool isBgmSpeedHold();
    void setBgmOutputRate(int rate) { bgmOutputRate = rate; }
    melonDS::NDS* bgmNds() override { return nds; }
    int bgmAudioFreq() override { return bgmOutputRate; }
    std::string bgmStorageDir() override;
    // 소리 분류 화면 (앱): 문자열 명령, 재생 소리
    std::string catalogCommand(const std::string& cmd);
    int previewRender(s16* stereo, int frames, int sampleRate);

private:
    void applyBgmConfiguration();
    void updateRenderer();
    void setBatteryLevels();
    void setDateTime();
    void saveRewindState(RewindSaveState* rewindSaveState);

private:
    int instanceId;
    int consoleType;
    NDS* nds;
    std::shared_ptr<Net> net;

    std::atomic<float> motionData[6] = { 0.0f, 0.0f, 9.80665f, 0.0f, 0.0f, 0.0f };

    std::unique_ptr<RetroAchievements::RetroAchievementsManager> retroAchievementsManager;
    std::unique_ptr<SaveManager> ndsSave;
    std::unique_ptr<SaveManager> gbaSave;
    std::unique_ptr<SaveManager> firmwareSave;
    u32 inputMask;

    std::shared_ptr<EmulatorConfiguration> currentConfiguration;
    FrameQueue frameQueue;
    std::unique_ptr<ScreenshotRenderer> screenshotRenderer;
    RewindManager rewindManager;
    Renderer currentRenderer;
    bool isRenderConfigurationDirty;
    int frame;

    std::unique_ptr<BgmReplacer> bgmReplacer;
    SoundPreview soundPreview;
    std::atomic<int> bgmOutputRate {48000};
};

}

#endif
