
# План разработки Android-секвенсора для live-выступлений

## 1. Цель проекта

Разработать приложение-секвенсор для Android 10, предназначенное для live-выступлений с использованием внешней USB MIDI-клавиатуры.

Приложение должно позволять:

* подключать MIDI-клавиатуру через USB OTG;
* играть на виртуальных инструментах из файлов SoundFont 2 (`.sf2`);
* воспроизводить заранее подготовленные MIDI-дорожки;
* воспроизводить аудиофонограммы;
* запускать сцены и клипы во время выступления;
* переключать инструменты и сцены с MIDI-клавиатуры;
* управлять громкостью, панорамой, mute и solo;
* сохранять и загружать проекты;
* сохранять стабильное воспроизведение при сворачивании интерфейса;
* минимизировать задержку между нажатием клавиши и появлением звука.

Целевая версия Android:

```text
Android 10
API 29
```

Минимальная версия для первоначальной разработки может быть:

```text
minSdk 26
targetSdk 29 или выше
```

Архитектура должна сохранять совместимость с Android 10, даже если сборка выполняется современным Android SDK.

---

# 2. Основные принципы архитектуры

Проект необходимо разделить на два уровня:

```text
Kotlin / Android
    интерфейс, сервис, управление проектами,
    обнаружение устройств, файловая система

C++ Native Engine
    аудиовывод, MIDI-маршрутизация,
    секвенсор, синтез, микшер, транспорт
```

Нельзя строить основной музыкальный движок на:

* `MediaPlayer`;
* `SoundPool`;
* `Handler`;
* `Timer`;
* Kotlin coroutines;
* нескольких независимых аудиопроигрывателях.

Все музыкальные компоненты должны использовать единый источник времени — позицию аудиопотока в сэмплах.

---

# 3. Общая архитектура

```text
┌─────────────────────────────────────────────────────┐
│                    Android UI                       │
│                     Kotlin                          │
│                                                     │
│  MainActivity                                       │
│  ├── экран сессии                                   │
│  ├── сцены и клипы                                  │
│  ├── микшер                                         │
│  ├── экран инструментов                             │
│  ├── MIDI Mapping                                   │
│  └── настройки аудио                                │
│                                                     │
│  ViewModel                                          │
│  ├── UI State                                       │
│  ├── Project State                                  │
│  └── команды сервису                                │
└──────────────────────┬──────────────────────────────┘
                       │ Binder / Flow
┌──────────────────────▼──────────────────────────────┐
│              Playback Foreground Service            │
│                                                     │
│  ├── владеет NativeEngine                           │
│  ├── управляет Audio Focus                          │
│  ├── обрабатывает жизненный цикл                    │
│  ├── создаёт постоянное уведомление                 │
│  ├── управляет MIDI-устройствами                    │
│  └── передаёт состояние в UI                        │
└──────────────────────┬──────────────────────────────┘
                       │ JNI
┌──────────────────────▼──────────────────────────────┐
│                Native Audio Engine                  │
│                      C++                            │
│                                                     │
│  Transport                                          │
│  Sequencer                                          │
│  MIDI Router                                        │
│  MIDI Recorder                                      │
│  Scene Manager                                      │
│  Clip Scheduler                                     │
│  FluidSynth Adapter                                 │
│  Audio Clip Player                                  │
│  Mixer                                              │
│  Effects                                            │
│  Master Limiter                                     │
│  Oboe Audio Output                                  │
└─────────────────────────────────────────────────────┘
```

---

# 4. Архитектурные модули

## 4.1 Android UI

UI реализовать на Kotlin.

Допускается использование:

```text
Jetpack Compose
```

или обычных Android Views.

Для первой версии предпочтительно использовать Jetpack Compose, если агент уверенно работает с ним. В противном случае использовать XML Views.

UI не должен напрямую управлять аудиопотоком.

UI должен отправлять в сервис высокоуровневые команды:

```text
Play
Stop
Pause
LaunchScene
LaunchClip
StopClip
SetTrackVolume
SetTrackPan
SetTrackMute
SetTrackSolo
SelectInstrument
LoadProject
SaveProject
Panic
```

UI должен получать от сервиса:

```text
текущую позицию транспорта;
номер текущего такта;
активную сцену;
состояния клипов;
уровни громкости;
подключённое MIDI-устройство;
текущий инструмент;
ошибки аудиодвижка.
```

Частота обновления UI:

```text
20–30 обновлений в секунду
```

Не обновлять UI на каждый аудиобуфер.

---

## 4.2 Foreground Playback Service

Создать Android foreground service:

```text
PlaybackService
```

Сервис должен:

* запускать и останавливать native audio engine;
* продолжать воспроизведение после закрытия Activity;
* владеть всеми ресурсами аудиодвижка;
* обрабатывать audio focus;
* обрабатывать подключение и отключение MIDI-устройств;
* создавать постоянное уведомление;
* предоставлять Binder API для UI;
* восстанавливать UI после пересоздания Activity;
* корректно освобождать ресурсы.

Activity не должна владеть аудиодвижком.

Жизненный цикл:

```text
Activity создана
    → подключается к PlaybackService

PlaybackService создан
    → создаёт NativeEngine
    → открывает MIDI
    → запускает foreground notification

Activity уничтожена
    → воспроизведение продолжается

PlaybackService остановлен
    → NativeEngine корректно закрывается
```

---

## 4.3 Native Audio Engine

Native engine реализовать на C++17 или C++20.

Основные компоненты:

```text
NativeEngine
Transport
Sequencer
MidiRouter
MidiRecorder
SceneManager
ClipScheduler
SynthEngine
AudioClipEngine
Mixer
MasterBus
AudioOutput
```

Главный класс:

```cpp
class NativeEngine {
public:
    bool start();
    void stop();

    void play();
    void pause();
    void seekToTick(int64_t tick);

    void launchScene(int sceneId);
    void launchClip(int trackId, int clipId);

    void setTrackVolume(int trackId, float volume);
    void setTrackPan(int trackId, float pan);
    void setTrackMute(int trackId, bool mute);
    void setTrackSolo(int trackId, bool solo);

    void noteOn(int channel, int note, int velocity);
    void noteOff(int channel, int note);
    void controlChange(int channel, int controller, int value);

    void panic();
};
```

---

# 5. Аудиовывод

Для аудиовывода использовать:

```text
Oboe
```

Настройки потока:

```text
PerformanceMode::LowLatency
SharingMode::Exclusive с fallback на Shared
AudioFormat::Float
Stereo output
Callback mode
```

Не задавать частоту дискретизации жёстко, если устройство предоставляет предпочтительную частоту.

После открытия потока сохранить:

```text
sample rate;
frames per burst;
buffer capacity;
device ID;
channel count.
```

Начальный размер буфера:

```text
2 × framesPerBurst
```

При underrun размер буфера можно увеличивать:

```text
3 × framesPerBurst
4 × framesPerBurst
```

Необходимо вести счётчик underrun.

---

# 6. Ограничения real-time аудиопотока

Внутри Oboe callback запрещены:

* выделение динамической памяти;
* `new`;
* `delete`;
* `malloc`;
* `free`;
* mutex;
* ожидание condition variable;
* файловые операции;
* логирование;
* JNI-вызовы;
* вызовы Kotlin;
* загрузка SoundFont;
* декодирование MP3 или FLAC;
* доступ к Room;
* изменение сложных контейнеров;
* блокирующие системные вызовы.

Все буферы должны быть выделены заранее.

Команды должны поступать через lock-free очереди.

---

# 7. Единый транспорт и синхронизация

Единственным источником музыкального времени является счётчик аудиофреймов.

Структура транспорта:

```cpp
struct TransportState {
    int64_t framePosition = 0;
    double sampleRate = 48000.0;

    double bpm = 120.0;

    int ppq = 960;
    int numerator = 4;
    int denominator = 4;

    bool playing = false;
};
```

Не использовать в качестве главного транспорта:

* `System.currentTimeMillis`;
* `System.nanoTime`;
* `Handler.postDelayed`;
* `delay`;
* `Timer`;
* MIDI clock отдельного синтезатора;
* внутренний sequencer FluidSynth.

Временные функции:

```cpp
double framesPerBeat() const;
double ticksPerFrame() const;

int64_t tickToFrame(int64_t tick) const;
int64_t frameToTick(int64_t frame) const;

int64_t getNextBeatFrame() const;
int64_t getNextBarFrame() const;
```

---

# 8. Sample-accurate scheduler

MIDI-события должны обрабатываться с точностью до сэмпла.

Алгоритм обработки одного Oboe callback:

```text
1. Получить currentFrame.
2. Определить endFrame = currentFrame + numFrames.
3. Найти события между currentFrame и endFrame.
4. Отрендерить звук до первого события.
5. Применить событие.
6. Продолжить рендер до следующего события.
7. Смешать аудиоклипы.
8. Выполнить обработку master bus.
9. Записать итоговый буфер в Oboe output.
10. Увеличить framePosition.
```

Пример структуры события:

```cpp
enum class MidiEventType {
    NoteOn,
    NoteOff,
    ControlChange,
    ProgramChange,
    PitchBend,
    ChannelPressure
};

struct ScheduledMidiEvent {
    int64_t targetFrame;
    MidiEventType type;

    uint8_t channel;
    uint8_t data1;
    uint8_t data2;
};
```

События внутри одного callback должны быть отсортированы по `targetFrame`.

---

# 9. MIDI-подсистема

## 9.1 Android MIDI API

На Kotlin использовать:

```text
MidiManager
MidiDevice
MidiInputPort
MidiOutputPort
MidiDeviceCallback
```

Необходимо реализовать:

* обнаружение MIDI-устройств;
* получение списка устройств;
* подключение выбранного устройства;
* автоматическое подключение последнего устройства;
* обработку отключения устройства;
* отображение имени устройства;
* выбор MIDI-порта;
* получение MIDI-сообщений.

При наличии возможности использовать Native MIDI API Android 10:

```text
AMidi
```

Допустим первый этап через Kotlin MIDI API с передачей MIDI-событий в C++ через JNI, но архитектура должна позволять заменить его на AMidi.

---

## 9.2 MIDI Router

Создать модуль:

```text
MidiRouter
```

Он должен поддерживать:

* фильтрацию по MIDI-каналу;
* переназначение каналов;
* split keyboard;
* transpose;
* velocity curve;
* маршрутизацию на live-инструмент;
* маршрутизацию на несколько треков;
* MIDI learn;
* обработку sustain pedal;
* управление сценами и клипами;
* запись MIDI.

Пример настройки маршрута:

```json
{
  "inputChannel": 1,
  "targetTrack": 0,
  "transpose": 0,
  "minNote": 0,
  "maxNote": 127,
  "velocityScale": 1.0
}
```

---

## 9.3 Live-режим

Live MIDI события должны обрабатываться с минимально возможной задержкой.

Для live-события:

```text
MIDI сообщение получено
    → помещено в lock-free queue
    → обработано в ближайшем audio callback
```

Не применять к live-событию квантизацию, если пользователь явно её не включил.

---

## 9.4 Запись MIDI

При записи MIDI необходимо сохранить исходный timestamp.

Затем timestamp преобразовать в позицию транспорта.

Структура записанного события:

```cpp
struct RecordedMidiEvent {
    int64_t tick;
    MidiEventType type;

    uint8_t channel;
    uint8_t data1;
    uint8_t data2;
};
```

После записи пользователь может применить квантизацию:

```text
Off
1/4
1/8
1/16
1/32
Triplet
```

Квантизация должна изменять только сохранённую позицию события, а не live monitoring.

---

# 10. Синтезатор SoundFont

Использовать:

```text
FluidSynth
```

FluidSynth должен работать как библиотека внутри native engine.

Нельзя использовать внутренний аудиодрайвер FluidSynth.

Правильная схема:

```text
MIDI Router
    → Scheduled MIDI Events
    → FluidSynth
    → PCM float buffer
    → Mixer
    → Oboe
```

Не использовать:

```text
FluidSynth audio driver
FluidSynth internal sequencer
```

В первой версии использовать один экземпляр FluidSynth.

Каждый трек назначается на MIDI-канал:

```text
Track 1 → MIDI channel 1
Track 2 → MIDI channel 2
Track 3 → MIDI channel 3
Drums   → MIDI channel 10
```

Необходимо реализовать:

* загрузку SF2;
* выгрузку SF2;
* выбор банка;
* выбор программы;
* Program Change;
* Note On;
* Note Off;
* Control Change;
* Pitch Bend;
* sustain;
* master gain;
* настройку polyphony;
* сброс всех нот.

Загрузка SoundFont выполняется только в worker thread.

При загрузке нового SF2 нельзя блокировать аудиопоток.

Желательная схема:

```text
Asset worker:
    создаёт новый SynthEngine
    загружает SF2
    подготавливает программы

Audio thread:
    на границе callback атомарно переключает SynthEngine
```

Для MVP допускается остановка воспроизведения во время загрузки SF2.

---

# 11. Panic

Обязательно реализовать глобальную команду:

```text
Panic
```

Она должна для всех MIDI-каналов отправлять:

```text
CC 64 = 0
CC 120 = 0
CC 121 = 0
CC 123 = 0
```

Дополнительно необходимо явно отключить все активные ноты, отслеживаемые движком.

Panic должен быть доступен:

* отдельной кнопкой в UI;
* из notification;
* через назначаемую MIDI-клавишу или кнопку.

---

# 12. Дорожки

Типы дорожек первой версии:

```text
Instrument Track
Audio Track
```

Позже можно добавить:

```text
MIDI Output Track
Group Track
Return Track
```

## 12.1 Instrument Track

Содержит:

```text
MIDI input settings;
MIDI channel;
SoundFont bank;
SoundFont program;
MIDI clips;
volume;
pan;
mute;
solo;
record arm;
transpose.
```

## 12.2 Audio Track

Содержит:

```text
audio clips;
volume;
pan;
mute;
solo;
loop settings;
start offset.
```

---

# 13. Клипы

Базовый интерфейс должен быть построен вокруг клипов и сцен, а не вокруг классической линейной DAW.

Типы клипов:

```text
MidiClip
AudioClip
```

Общий интерфейс:

```cpp
class Clip {
public:
    int id;
    int trackId;

    int64_t lengthTicks;

    bool loopEnabled;
    bool playing;
    bool queued;
};
```

---

## 13.1 MIDI Clip

Структура:

```cpp
struct MidiClip {
    int id;
    int trackId;

    int64_t lengthTicks;
    bool loopEnabled;

    std::vector<RecordedMidiEvent> events;
};
```

Для real-time воспроизведения не использовать `std::vector` напрямую с модификацией из аудиопотока.

Перед запуском создать immutable runtime representation.

---

## 13.2 Audio Clip

Audio clip должен воспроизводить заранее декодированный PCM или использовать ring buffer.

Для MVP рекомендуется поддержать:

```text
WAV PCM
```

После стабильной реализации добавить:

```text
MP3
AAC
FLAC
OGG
```

Архитектура декодирования:

```text
MediaExtractor / MediaCodec
    → Decode Worker
    → PCM Ring Buffer
    → Audio Callback
```

Для коротких файлов допускается полное декодирование в память.

Для длинной фонограммы:

```text
decode worker
    → поддерживает запас PCM на несколько секунд
    → помещает данные в ring buffer
```

Audio callback только читает готовые PCM-данные.

---

# 14. Сцены

Сцена представляет собой набор клипов, запускаемых одновременно или с общей квантизацией.

Пример:

```text
Scene 1: Intro
    Piano Track → Intro MIDI clip
    Pad Track → Intro Pad clip
    Audio Track → Intro backing track

Scene 2: Verse
    Piano Track → Verse MIDI clip
    Pad Track → Verse Pad clip
    Audio Track → Verse backing track
```

Структура:

```cpp
struct Scene {
    int id;
    std::string name;

    std::vector<int> clipIds;
};
```

При запуске сцены команда не обязательно исполняется немедленно.

Она должна получить рассчитанную позицию:

```cpp
struct LaunchSceneCommand {
    int sceneId;
    int64_t targetFrame;
};
```

---

# 15. Квантизация запуска

Поддержать режимы:

```text
Immediate
1/4
1/2
1 Bar
2 Bars
4 Bars
```

При нажатии сцены:

```text
UI отправляет LaunchScene(sceneId)
    → control thread определяет quantization mode
    → рассчитывает targetFrame
    → помещает команду в audio queue
    → сцена запускается точно на targetFrame
```

---

# 16. Микшер

Каждая дорожка должна иметь:

```text
volume;
pan;
mute;
solo;
peak meter;
optional gain.
```

Диапазон громкости:

```text
0.0–1.0
```

или в децибелах:

```text
-∞ dB ... +6 dB
```

Панорама:

```text
-1.0 — left
 0.0 — center
+1.0 — right
```

Для панорамы использовать equal-power panning.

Структура:

```cpp
struct TrackMixerState {
    float volume;
    float pan;

    bool mute;
    bool solo;
};
```

На master bus добавить:

```text
master volume;
soft clipper или limiter;
peak meter.
```

Не допускать жёсткого clipping при суммировании нескольких дорожек.

---

# 17. Межпоточная архитектура

Необходимо использовать несколько потоков.

```text
Main/UI thread
Playback service thread
Audio callback thread
MIDI input thread
Control thread
Audio decode worker
Asset loading worker
File I/O worker
```

## 17.1 Команды в аудиопоток

Использовать lock-free SPSC или MPSC queue.

Пример команды:

```cpp
enum class EngineCommandType {
    Play,
    Pause,
    Stop,
    Seek,

    LaunchScene,
    LaunchClip,
    StopClip,

    SetVolume,
    SetPan,
    SetMute,
    SetSolo,

    NoteOn,
    NoteOff,
    ControlChange,
    ProgramChange,

    SwapProjectSnapshot,
    Panic
};

struct EngineCommand {
    EngineCommandType type;

    int trackId;
    int objectId;

    int64_t targetFrame;

    int intValue1;
    int intValue2;

    float floatValue;
};
```

Очередь должна быть заранее выделена.

При переполнении очередь должна:

* возвращать ошибку;
* увеличивать счётчик dropped commands;
* не блокировать аудиопоток.

---

## 17.2 Данные из аудиопотока

Отдельная очередь или набор атомарных значений для:

```text
transport position;
active scene;
active clips;
track peak levels;
master peak;
underrun count;
CPU load;
error flags.
```

UI читает эти данные периодически.

---

# 18. Снимки состояния

Не передавать изменяемые Kotlin-объекты напрямую в аудиопоток.

Использовать три уровня состояния:

```text
ProjectState
RuntimeSnapshot
RealtimeState
```

## ProjectState

Редактируемая модель проекта в Kotlin.

## RuntimeSnapshot

Неизменяемое подготовленное состояние для native engine.

Содержит:

```text
список треков;
сцены;
клипы;
отсортированные MIDI-события;
маршрутизацию;
параметры микшера.
```

## RealtimeState

Изменяется только аудиопотоком:

```text
frame position;
playing state;
active clips;
active notes;
meters.
```

Обновление проекта:

```text
UI изменяет ProjectState
    → background thread создаёт RuntimeSnapshot
    → snapshot передаётся в NativeEngine
    → audio thread активирует его на границе callback
```

---

# 19. Формат проекта

Проект должен храниться в отдельном каталоге.

Пример:

```text
MyLiveSet/
├── project.json
├── soundfonts/
│   └── main.sf2
├── midi/
│   ├── intro.mid
│   ├── verse.mid
│   └── chorus.mid
├── audio/
│   └── backing.wav
└── cache/
    └── backing.pcm
```

Файл `project.json`:

```json
{
  "formatVersion": 1,
  "name": "My Live Set",
  "tempo": 120.0,
  "timeSignature": {
    "numerator": 4,
    "denominator": 4
  },
  "ppq": 960,
  "launchQuantization": "BAR_1",
  "tracks": [],
  "scenes": [],
  "midiMappings": []
}
```

Формат должен быть версионирован:

```text
formatVersion
```

При изменениях схемы необходимо создавать миграции.

---

# 20. Работа с файлами Android

Для выбора файлов использовать:

```text
Storage Access Framework
```

Поддержать:

```text
ACTION_OPEN_DOCUMENT
ACTION_OPEN_DOCUMENT_TREE
```

После выбора файла рекомендуется копировать его в каталог проекта.

Не проигрывать live-проект напрямую из случайного Content URI, если файл может исчезнуть или provider работает медленно.

Проверять:

* существование файла;
* MIME type;
* доступ на чтение;
* размер файла;
* свободное место;
* успешное копирование;
* контрольную сумму при необходимости.

---

# 21. MIDI Mapping

Пользователь должен иметь возможность назначить MIDI-событие на действие.

Поддерживаемые действия:

```text
Launch Scene
Next Scene
Previous Scene
Play
Stop
Pause
Panic
Track Mute
Track Solo
Track Volume
Master Volume
Program Change
```

Пример:

```json
{
  "messageType": "NOTE_ON",
  "channel": 1,
  "data1": 36,
  "action": "LAUNCH_SCENE",
  "targetId": 2
}
```

MIDI Learn:

```text
1. Пользователь нажимает Learn.
2. Выбирает действие.
3. Нажимает клавишу или кнопку на MIDI-контроллере.
4. Приложение сохраняет полученное MIDI-сообщение.
5. Последующие такие сообщения вызывают назначенное действие.
```

---

# 22. Audio Focus и жизненный цикл

Реализовать обработку:

```text
AUDIOFOCUS_GAIN
AUDIOFOCUS_LOSS
AUDIOFOCUS_LOSS_TRANSIENT
AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
```

При полной потере focus:

```text
sustain off;
all notes off;
остановить или поставить транспорт на паузу;
сохранить текущую позицию.
```

При временной потере:

```text
выполнить pause или duck согласно настройкам.
```

При возврате focus:

```text
восстановить аудиопоток;
не запускать зависшие ноты;
продолжить только при разрешённой настройке.
```

---

# 23. Обработка отключения устройств

Необходимо обработать:

* отключение MIDI-клавиатуры;
* подключение MIDI-клавиатуры;
* отключение USB-аудиоинтерфейса;
* отключение проводных наушников;
* изменение output device;
* ошибку Oboe stream;
* перезапуск audio stream.

При отключении MIDI:

```text
выполнить Panic;
показать предупреждение;
продолжить воспроизведение подготовленных клипов.
```

При ошибке аудиопотока:

```text
остановить callback;
сохранить позицию транспорта;
переоткрыть поток;
восстановить движок;
продолжить или ждать команды пользователя.
```

---

# 24. Интерфейс первой версии

## 24.1 Главный экран Session

Экран должен содержать:

```text
верхняя панель:
    Play
    Stop
    BPM
    позиция такт:доля
    Panic
    подключённое MIDI-устройство

центральная часть:
    строки — треки
    столбцы — сцены
    ячейки — клипы

нижняя или боковая панель:
    выбранный трек
    инструмент
    volume
    pan
    mute
    solo
    arm
```

## 24.2 Экран инструментов

Функции:

```text
выбор SF2;
список банков;
список программ;
предпрослушивание;
polyphony;
gain;
transpose;
MIDI channel.
```

## 24.3 Экран MIDI

Функции:

```text
список устройств;
выбор устройства;
выбор порта;
индикатор MIDI activity;
MIDI channel filter;
MIDI Learn;
Panic.
```

## 24.4 Экран проекта

Функции:

```text
создать проект;
открыть проект;
сохранить;
сохранить как;
импорт SF2;
импорт MIDI;
импорт audio;
экспорт проекта.
```

---

# 25. Предлагаемая структура репозитория

```text
live-sequencer/
├── app/
│   ├── src/main/java/.../
│   │   ├── MainActivity.kt
│   │   ├── service/
│   │   │   ├── PlaybackService.kt
│   │   │   ├── PlaybackBinder.kt
│   │   │   └── PlaybackNotification.kt
│   │   ├── midi/
│   │   │   ├── MidiDeviceManager.kt
│   │   │   ├── MidiInputReceiver.kt
│   │   │   └── MidiDeviceState.kt
│   │   ├── project/
│   │   │   ├── Project.kt
│   │   │   ├── Track.kt
│   │   │   ├── Scene.kt
│   │   │   ├── Clip.kt
│   │   │   ├── ProjectRepository.kt
│   │   │   └── ProjectSerializer.kt
│   │   ├── ui/
│   │   │   ├── session/
│   │   │   ├── mixer/
│   │   │   ├── instrument/
│   │   │   ├── midi/
│   │   │   └── settings/
│   │   └── nativebridge/
│   │       └── NativeEngineBridge.kt
│   │
│   └── src/main/cpp/
│       ├── CMakeLists.txt
│       ├── jni/
│       │   └── native_engine_jni.cpp
│       ├── engine/
│       │   ├── NativeEngine.cpp
│       │   ├── NativeEngine.h
│       │   ├── Transport.cpp
│       │   ├── Transport.h
│       │   ├── Sequencer.cpp
│       │   ├── Sequencer.h
│       │   ├── SceneManager.cpp
│       │   ├── ClipScheduler.cpp
│       │   ├── MidiRouter.cpp
│       │   ├── MidiRecorder.cpp
│       │   ├── Mixer.cpp
│       │   └── MasterBus.cpp
│       ├── audio/
│       │   ├── OboeOutput.cpp
│       │   ├── OboeOutput.h
│       │   ├── AudioClipPlayer.cpp
│       │   ├── AudioDecoderBridge.cpp
│       │   └── RingBuffer.h
│       ├── synth/
│       │   ├── FluidSynthEngine.cpp
│       │   └── FluidSynthEngine.h
│       ├── realtime/
│       │   ├── CommandQueue.h
│       │   ├── MidiQueue.h
│       │   ├── MeterQueue.h
│       │   └── RealtimeState.h
│       └── model/
│           ├── RuntimeProject.h
│           ├── RuntimeTrack.h
│           ├── RuntimeScene.h
│           └── RuntimeClip.h
│
├── third_party/
│   ├── oboe/
│   └── fluidsynth/
│
├── docs/
│   ├── architecture.md
│   ├── realtime-rules.md
│   ├── project-format.md
│   └── testing.md
│
└── README.md
```

---

# 26. Этапы разработки

## Этап 0. Подготовка проекта

Задачи:

1. Создать Android-проект.
2. Настроить Kotlin.
3. Настроить Android NDK.
4. Настроить CMake.
5. Подключить Oboe.
6. Подключить FluidSynth.
7. Создать JNI bridge.
8. Добавить CI-сборку.
9. Добавить базовый logging.
10. Создать архитектурную документацию.

Результат:

```text
Приложение собирается.
Kotlin вызывает тестовый C++ метод через JNI.
CI создаёт APK.
```

Критерий готовности:

```text
На Android 10 приложение запускается без crash.
```

---

## Этап 1. Минимальный аудиовывод

Задачи:

1. Реализовать `OboeOutput`.
2. Открыть low-latency stream.
3. Создать callback.
4. Сгенерировать тестовый синус.
5. Реализовать start и stop.
6. Добавить underrun counter.
7. Добавить обработку stream error.
8. Проверить на Android 10.

Результат:

```text
Приложение воспроизводит стабильный тестовый тон.
```

Критерии готовности:

* нет щелчков при обычной работе;
* start и stop работают многократно;
* Activity можно пересоздать;
* утечки отсутствуют;
* stream восстанавливается после ошибки.

---

## Этап 2. FluidSynth и SF2

Задачи:

1. Создать `FluidSynthEngine`.
2. Подключить рендер PCM float.
3. Отключить собственный audio driver FluidSynth.
4. Реализовать загрузку SF2.
5. Реализовать выбор банка и программы.
6. Реализовать Note On и Note Off.
7. Реализовать sustain.
8. Реализовать Pitch Bend.
9. Реализовать Panic.
10. Добавить простую экранную клавиатуру для тестов.

Результат:

```text
Пользователь выбирает SF2 и играет экранными клавишами.
```

Критерии готовности:

* ноты звучат без зависаний;
* Panic отключает все ноты;
* смена программы работает;
* sustain работает;
* повторная загрузка проекта не приводит к утечкам.

---

## Этап 3. USB MIDI

Задачи:

1. Реализовать `MidiDeviceManager`.
2. Получить список MIDI-устройств.
3. Подключить MIDI output port устройства ко входу приложения.
4. Разобрать MIDI-сообщения.
5. Передать события в C++.
6. Реализовать MIDI activity indicator.
7. Реализовать автоматическое переподключение.
8. Выполнять Panic при отключении.
9. Добавить выбор MIDI-канала.
10. Проверить velocity и sustain pedal.

Результат:

```text
Внешняя USB MIDI-клавиатура управляет FluidSynth.
```

Критерии готовности:

* Note On и Note Off работают;
* velocity влияет на громкость;
* sustain работает;
* pitch bend работает;
* отключение кабеля не приводит к crash;
* после отключения не остаются зависшие ноты.

---

## Этап 4. Foreground service

Задачи:

1. Создать `PlaybackService`.
2. Переместить NativeEngine в сервис.
3. Создать Binder API.
4. Создать foreground notification.
5. Добавить Play, Stop и Panic в notification.
6. Обработать audio focus.
7. Обеспечить работу при закрытой Activity.
8. Обеспечить повторное подключение UI.
9. Сохранять актуальное состояние сервиса.
10. Проверить блокировку экрана.

Результат:

```text
Звук и MIDI продолжают работать при свёрнутом приложении.
```

Критерии готовности:

* закрытие Activity не останавливает звук;
* сервис корректно останавливается;
* notification отображается;
* после пересоздания UI показывает актуальное состояние;
* audio focus обрабатывается корректно.

---

## Этап 5. Transport

Задачи:

1. Реализовать frame-based transport.
2. Добавить BPM.
3. Добавить PPQ 960.
4. Добавить размер такта.
5. Реализовать tick-to-frame.
6. Реализовать frame-to-tick.
7. Реализовать Play.
8. Реализовать Pause.
9. Реализовать Stop.
10. Реализовать Seek.
11. Добавить метроном.
12. Выводить такт и долю в UI.

Результат:

```text
Работает единый музыкальный транспорт.
```

Критерии готовности:

* метроном не дрейфует;
* смена BPM изменяет темп;
* позиция соответствует аудиофреймам;
* Pause и resume сохраняют позицию;
* Stop возвращает позицию к началу.

---

## Этап 6. MIDI Sequencer

Задачи:

1. Реализовать `MidiClip`.
2. Реализовать импорт Standard MIDI File.
3. Преобразовать события MIDI в внутренний формат.
4. Отсортировать события.
5. Реализовать sample-accurate scheduler.
6. Реализовать looping.
7. Обработать Note Off на границе loop.
8. Реализовать Program Change.
9. Реализовать Control Change.
10. Реализовать запуск и остановку клипа.

Результат:

```text
Приложение воспроизводит импортированный MIDI-файл через FluidSynth.
```

Критерии готовности:

* MIDI не дрейфует относительно метронома;
* loop не создаёт зависших нот;
* события внутри callback исполняются с нужным offset;
* Stop немедленно отключает активные ноты.

---

## Этап 7. Запись MIDI

Задачи:

1. Добавить record arm.
2. Записывать MIDI input.
3. Сохранять timestamp.
4. Преобразовывать его в tick.
5. Создавать новый MIDI clip.
6. Реализовать overdub.
7. Реализовать quantization.
8. Реализовать отмену последней записи.
9. Реализовать экспорт MIDI.
10. Добавить count-in метронома.

Результат:

```text
Пользователь может записать партию с MIDI-клавиатуры и воспроизвести её.
```

Критерии готовности:

* Note On и Note Off сохраняются корректно;
* длительность нот не теряется;
* velocity сохраняется;
* квантизация не уничтожает Note Off;
* overdub не удаляет существующие события.

---

## Этап 8. Треки и микшер

Задачи:

1. Создать Instrument Track.
2. Поддержать несколько MIDI-каналов.
3. Реализовать volume.
4. Реализовать equal-power pan.
5. Реализовать mute.
6. Реализовать solo.
7. Реализовать peak meters.
8. Реализовать master gain.
9. Добавить limiter или soft clipper.
10. Добавить UI микшера.

Результат:

```text
Несколько инструментальных треков воспроизводятся одновременно.
```

Критерии готовности:

* mute и solo работают предсказуемо;
* громкость не щёлкает при изменении;
* параметры сглаживаются;
* master bus не клиппирует при нормальной настройке;
* meters обновляются без нагрузки на UI.

---

## Этап 9. Сцены и Session View

Задачи:

1. Создать модель Scene.
2. Связать сцену с клипами.
3. Реализовать запуск сцены.
4. Реализовать launch quantization.
5. Реализовать очередь запуска.
6. Реализовать Next Scene.
7. Реализовать Previous Scene.
8. Добавить статус queued.
9. Добавить статус playing.
10. Создать Session View.

Результат:

```text
Пользователь запускает Intro, Verse, Chorus и другие сцены во время выступления.
```

Критерии готовности:

* сцена стартует точно на следующем такте;
* UI показывает queued-состояние;
* старая сцена корректно останавливается;
* при переключении не остаются зависшие ноты;
* повторное нажатие не создаёт дублирующий запуск.

---

## Этап 10. Аудиофонограммы

Задачи:

1. Добавить Audio Track.
2. Для MVP поддержать WAV PCM.
3. Реализовать загрузку WAV.
4. Реализовать PCM playback.
5. Реализовать loop.
6. Синхронизировать audio clip с transport.
7. Реализовать volume, pan, mute и solo.
8. Добавить ring buffer.
9. Добавить decode worker.
10. Подготовить интерфейс для MediaCodec.

Результат:

```text
Аудиофонограмма воспроизводится синхронно с MIDI-клипами.
```

Критерии готовности:

* фонограмма стартует на нужном такте;
* воспроизведение не зависит от отдельного MediaPlayer;
* позиция не дрейфует;
* seek корректно переставляет позицию;
* нехватка данных в ring buffer не блокирует callback.

---

## Этап 11. Формат проекта

Задачи:

1. Создать Kotlin-модель Project.
2. Создать JSON serializer.
3. Добавить `formatVersion`.
4. Реализовать создание проекта.
5. Реализовать сохранение.
6. Реализовать загрузку.
7. Реализовать импорт ресурсов.
8. Реализовать копирование в каталог проекта.
9. Добавить проверку отсутствующих файлов.
10. Добавить autosave метаданных.

Результат:

```text
Live set можно сохранить, закрыть приложение и открыть снова.
```

Критерии готовности:

* проект восстанавливается без потери настроек;
* относительные пути корректны;
* отсутствие SF2 или аудиофайла выдаёт понятную ошибку;
* повреждённый JSON не приводит к crash;
* версия формата проверяется.

---

## Этап 12. MIDI Learn

Задачи:

1. Создать модель MidiMapping.
2. Реализовать режим Learn.
3. Назначить MIDI note на запуск сцены.
4. Назначить CC на громкость.
5. Назначить MIDI note на Panic.
6. Реализовать Next Scene.
7. Реализовать Previous Scene.
8. Добавить конфликт mappings.
9. Сохранять mappings в проект.
10. Добавить удаление mapping.

Результат:

```text
Выступлением можно управлять без касания экрана.
```

Критерии готовности:

* назначение сохраняется;
* MIDI-событие не запускает одновременно несколько конфликтующих действий без явной настройки;
* Panic имеет приоритет;
* mappings работают после перезапуска приложения.

---

# 27. Тестирование

## 27.1 Unit tests

Покрыть тестами:

```text
tick-to-frame;
frame-to-tick;
расчёт следующего такта;
launch quantization;
MIDI parser;
MIDI file parser;
MIDI recording;
quantization;
project serialization;
project migrations;
scene switching;
loop boundaries.
```

## 27.2 Native tests

Проверить:

```text
command queue;
ring buffer;
scheduler;
mixer;
pan law;
transport drift;
active note tracking;
Panic;
snapshot switching.
```

## 27.3 Instrumentation tests

Проверить:

```text
Activity recreation;
service binding;
foreground service;
file picker;
project loading;
permissions;
audio focus.
```

## 27.4 Ручные live-тесты

Тестировать минимум 60 минут непрерывно:

```text
MIDI keyboard connected;
несколько инструментов;
фонограмма;
переключение сцен;
sustain pedal;
mute/solo;
блокировка экрана;
переключение приложений.
```

Проверять:

* щелчки;
* зависшие ноты;
* рассинхронизацию;
* рост памяти;
* перегрев;
* потерю MIDI;
* underrun;
* crash.

---

# 28. Диагностика

Добавить экран диагностики:

```text
sample rate;
frames per burst;
buffer size;
output device;
sharing mode;
performance mode;
underrun count;
audio callback CPU load;
MIDI device;
MIDI messages per second;
queue overflow count;
audio ring buffer fill;
active voices;
FluidSynth polyphony;
application memory.
```

Логи должны быть отключаемыми.

Не выполнять logging из audio callback.

Вместо этого audio callback изменяет атомарные счётчики, а обычный поток периодически записывает их в лог.

---

# 29. Требования к производительности

Целевые показатели:

```text
стабильный audio callback;
отсутствие аллокаций в callback;
отсутствие mutex в callback;
MIDI-to-audio latency минимально возможная;
нет заметного дрейфа за 60 минут;
нет зависших нот после Stop или Panic;
нет постоянного роста памяти.
```

Приоритеты оптимизации:

```text
1. Стабильность звука.
2. Отсутствие зависших нот.
3. Предсказуемое переключение сцен.
4. Низкая задержка.
5. Удобство UI.
6. Дополнительные эффекты.
```

---

# 30. Что не реализовывать в первом MVP

Не включать в первую версию:

* VST;
* AU;
* LV2;
* сложный piano roll;
* time stretching;
* pitch shifting аудиофайлов;
* warping;
* автоматизацию параметров;
* полноценный waveform editor;
* многодорожечную аудиозапись;
* cloud synchronization;
* сетевую синхронизацию;
* Ableton Link;
* Bluetooth MIDI как основной сценарий;
* сложные insert-эффекты;
* поддержку десятков форматов;
* внутренний магазин инструментов.

Эти функции не должны задерживать создание стабильного live-MVP.

---

# 31. Приоритетный MVP

Первая реально полезная версия должна включать:

```text
USB MIDI keyboard;
Oboe low-latency output;
FluidSynth;
загрузка SF2;
выбор инструмента;
несколько инструментальных треков;
MIDI clips;
метроном;
transport;
сцены;
launch quantization;
volume;
pan;
mute;
solo;
Panic;
foreground service;
сохранение проекта;
MIDI Learn для переключения сцен.
```

Аудиофонограммы можно добавить сразу после стабильной работы MIDI-сцен.

---

# 32. Правила работы coding-агента

Агент должен работать небольшими завершёнными итерациями.

Для каждого этапа агент обязан:

1. Изучить существующую архитектуру.
2. Не дублировать уже существующие компоненты.
3. Сначала описать изменяемые интерфейсы.
4. Реализовать минимально достаточный объём.
5. Добавить тесты.
6. Собрать проект.
7. Исправить ошибки компиляции.
8. Запустить доступные тесты.
9. Обновить документацию.
10. Выдать отчёт о сделанных изменениях.

После каждого этапа агент должен предоставить:

```text
Изменённые файлы.
Новые классы и интерфейсы.
Команды сборки.
Результаты тестов.
Известные ограничения.
Следующий рекомендуемый этап.
```

Агент не должен:

* переписывать весь проект без необходимости;
* менять архитектуру без объяснения;
* смешивать UI и real-time код;
* использовать mutex в audio callback;
* выполнять JNI из audio callback;
* добавлять MediaPlayer как отдельный источник времени;
* использовать таймеры UI для музыкальной синхронизации;
* загружать SF2 из audio callback;
* скрывать ошибки сборки;
* оставлять незавершённые заглушки без маркировки.

---

# 33. Первый промпт для coding-агента

Начать работу следует с такого задания:

```text
Создай каркас Android-приложения live-секвенсора для Android 10.

Технологии:
- Kotlin;
- Android NDK;
- CMake;
- C++20;
- Oboe;
- FluidSynth;
- JNI.

Архитектура:
- Android UI на Kotlin;
- Foreground PlaybackService владеет NativeEngine;
- NativeEngine на C++;
- Oboe используется как единственный аудиовыход;
- FluidSynth рендерит PCM во внутренний микшер;
- музыкальное время основано на счётчике аудиофреймов;
- взаимодействие с audio callback только через заранее выделенные lock-free очереди;
- внутри audio callback запрещены аллокации, mutex, файловый ввод-вывод, JNI и логирование.

На первом этапе реализуй:
1. Android-проект с minSdk 26 и поддержкой Android 10.
2. Подключение NDK и CMake.
3. Kotlin-класс NativeEngineBridge.
4. JNI создание и удаление NativeEngine.
5. Oboe low-latency output.
6. Тестовый синусоидальный генератор.
7. Start и Stop из Kotlin.
8. Обработку ошибок Oboe stream.
9. Счётчик underrun.
10. README с инструкцией сборки.

Не добавляй MIDI, FluidSynth, секвенсор и сложный UI до завершения стабильного Oboe-аудиовывода.

После реализации:
- собери debug APK;
- запусти unit tests;
- перечисли изменённые файлы;
- укажи команды сборки;
- опиши ограничения;
- не переходи к следующему этапу автоматически.
```

---

# 34. Итоговая архитектурная цель

Финальная схема обработки должна выглядеть так:

```text
USB MIDI Keyboard
        │
        ▼
Android MIDI API / AMidi
        │
        ▼
MIDI Router
        │
        ├── Live MIDI
        ├── MIDI Recording
        ├── MIDI Mapping
        └── Scene Control
        │
        ▼
Sample-Accurate Scheduler
        │
        ▼
FluidSynth
        │
        ▼
Instrument Track Buffers
        │
        ├───────────────────────┐
        │                       │
        ▼                       ▼
Audio Clip Buffers          Other Tracks
        │                       │
        └───────────┬───────────┘
                    ▼
                  Mixer
                    │
                    ▼
            Master Limiter
                    │
                    ▼
                  Oboe
                    │
                    ▼
          Android Audio Device
```

Все компоненты должны синхронизироваться относительно одного transport frame counter.

Именно native audio engine должен определять:

* когда стартует MIDI-событие;
* когда стартует сцена;
* когда стартует аудиоклип;
* какая позиция транспорта активна;
* какие ноты звучат;
* как смешиваются дорожки;
* когда данные передаются в аудиоустройство.

Kotlin-часть должна управлять движком, но не определять музыкальное время.

