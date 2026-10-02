/**
 * Graffiti Custom Audio Player Component
 * Provides a touch-friendly, mobile-optimized audio player with:
 * - 100% full-width position seek bar with fat touch targets
 * - 10-second skip forward/backward buttons
 * - Playback rate switcher (1x / 1.25x / 1.5x / 2x)
 * - Auto-pausing of other audio streams when playing
 */

const PLAY_ICON = `<svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>`;
const PAUSE_ICON = `<svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/></svg>`;
const REWIND_10_ICON = `<svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6 0 2.97-2.16 5.43-5 5.91v2.02c3.95-.49 7-3.85 7-7.93 0-4.42-3.58-8-8-8zm-1.8 11.2h-1v-4.4l-1.2.4v-.8l2.1-.8h.1v5.6zm4.8-2.8c0 1.8-.8 2.9-2 2.9s-2-1.1-2-2.9.8-2.9 2-2.9 2 1.1 2 2.9zm-1 0c0-1.3-.3-2.1-1-2.1s-1 .8-1 2.1.3 2.1 1 2.1 1-.8 1-2.1z"/></svg>`;
const FORWARD_10_ICON = `<svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M12 5V1l5 5-5 5V7c-3.31 0-6 2.69-6 6 0 2.97 2.16 5.43 5 5.91v2.02c-3.95-.49-7-3.85-7-7.93 0-4.42 3.58-8 8-8zm-1.8 11.2h-1v-4.4l-1.2.4v-.8l2.1-.8h.1v5.6zm4.8-2.8c0 1.8-.8 2.9-2 2.9s-2-1.1-2-2.9.8-2.9 2-2.9 2 1.1 2 2.9zm-1 0c0-1.3-.3-2.1-1-2.1s-1 .8-1 2.1.3 2.1 1 2.1 1-.8 1-2.1z"/></svg>`;

const PLAYBACK_RATES = [1, 1.25, 1.5, 2];

let currentPlayingAudio: HTMLAudioElement | null = null;

export function formatAudioTime(seconds: number): string {
   if (isNaN(seconds) || !isFinite(seconds) || seconds < 0) return '0:00';
   const totalSec = Math.floor(seconds);
   const h = Math.floor(totalSec / 3600);
   const m = Math.floor((totalSec % 3600) / 60);
   const s = totalSec % 60;
   const pad = (n: number) => (n < 10 ? '0' + n : String(n));
   if (h > 0) {
      return `${h}:${pad(m)}:${pad(s)}`;
   }
   return `${m}:${pad(s)}`;
}

export interface CustomAudioPlayerHandle {
   playerElement: HTMLElement;
   audio: HTMLAudioElement;
   destroy: () => void;
}

/**
 * Attaches a custom, touch-friendly UI controller to an existing HTMLAudioElement.
 */
export function attachCustomAudioPlayer(
   audio: HTMLAudioElement,
   options?: { onEnded?: () => void }
): CustomAudioPlayerHandle {
   // Check if already attached
   const existingWrapper = audio.closest('.custom-audio-player') as HTMLElement | null;
   if (existingWrapper && (audio as any).__customPlayerHandle) {
      return (audio as any).__customPlayerHandle;
   }

   audio.controls = false; // Disable native browser controls

   const wrapper = document.createElement('div');
   wrapper.className = 'custom-audio-player';

   wrapper.innerHTML = `
      <div class="audio-controls-row">
         <button type="button" class="audio-btn audio-btn-play" title="Play / Pause" aria-label="Play">
            ${PLAY_ICON}
         </button>
         <button type="button" class="audio-btn audio-btn-skip audio-btn-rewind" title="Replay 10 seconds" aria-label="Replay 10 seconds">
            ${REWIND_10_ICON}
         </button>
         <button type="button" class="audio-btn audio-btn-skip audio-btn-forward" title="Skip forward 10 seconds" aria-label="Skip forward 10 seconds">
            ${FORWARD_10_ICON}
         </button>
         <div class="audio-time-display">
            <span class="audio-time-current">0:00</span>
            <span class="audio-time-sep">/</span>
            <span class="audio-time-duration">0:00</span>
         </div>
         <button type="button" class="audio-btn audio-btn-speed" title="Change speed" aria-label="Playback speed">1x</button>
      </div>
      <div class="audio-seek-row">
         <input type="range" class="audio-seek-slider" min="0" max="100" step="0.1" value="0" aria-label="Seek position" style="--seek-pct: 0%;">
      </div>
   `;

   // Insert wrapper in DOM where audio was
   if (audio.parentNode) {
      audio.parentNode.insertBefore(wrapper, audio);
      wrapper.prepend(audio);
   }

   const playBtn = wrapper.querySelector<HTMLButtonElement>('.audio-btn-play')!;
   const rewindBtn = wrapper.querySelector<HTMLButtonElement>('.audio-btn-rewind')!;
   const forwardBtn = wrapper.querySelector<HTMLButtonElement>('.audio-btn-forward')!;
   const speedBtn = wrapper.querySelector<HTMLButtonElement>('.audio-btn-speed')!;
   const timeCurrent = wrapper.querySelector<HTMLElement>('.audio-time-current')!;
   const timeDuration = wrapper.querySelector<HTMLElement>('.audio-time-duration')!;
   const seekSlider = wrapper.querySelector<HTMLInputElement>('.audio-seek-slider')!;

   let isScrubbing = false;
   let rateIndex = 0;

   const updatePlayState = (playing: boolean) => {
      playBtn.innerHTML = playing ? PAUSE_ICON : PLAY_ICON;
      playBtn.setAttribute('aria-label', playing ? 'Pause' : 'Play');
      if (playing) {
         wrapper.classList.add('is-playing');
      } else {
         wrapper.classList.remove('is-playing');
      }
   };

   const updateDuration = () => {
      if (audio.duration && !isNaN(audio.duration) && isFinite(audio.duration)) {
         timeDuration.textContent = formatAudioTime(audio.duration);
      } else {
         timeDuration.textContent = '0:00';
      }
   };

   const updateProgress = () => {
      if (isScrubbing) return;
      const duration = audio.duration;
      if (duration && !isNaN(duration) && duration > 0) {
         const pct = (audio.currentTime / duration) * 100;
         seekSlider.value = pct.toFixed(2);
         seekSlider.style.setProperty('--seek-pct', `${pct}%`);
         timeCurrent.textContent = formatAudioTime(audio.currentTime);
      } else {
         seekSlider.value = '0';
         seekSlider.style.setProperty('--seek-pct', '0%');
         timeCurrent.textContent = formatAudioTime(audio.currentTime);
      }
   };

   // Event Handlers
   const onPlay = () => {
      if (currentPlayingAudio && currentPlayingAudio !== audio) {
         currentPlayingAudio.pause();
      }
      currentPlayingAudio = audio;
      updatePlayState(true);
   };

   const onPause = () => {
      if (currentPlayingAudio === audio) {
         currentPlayingAudio = null;
      }
      updatePlayState(false);
   };

   const onTimeUpdate = () => {
      updateProgress();
   };

   const onLoadedMetadata = () => {
      updateDuration();
      updateProgress();
   };

   const onEnded = () => {
      updatePlayState(false);
      audio.currentTime = 0;
      updateProgress();
      if (options?.onEnded) {
         options.onEnded();
      }
   };

   // Button clicks
   playBtn.onclick = (e) => {
      e.preventDefault();
      e.stopPropagation();
      if (audio.paused) {
         audio.play().catch(err => console.warn('[AudioPlayer] Playback prevented:', err));
      } else {
         audio.pause();
      }
   };

   rewindBtn.onclick = (e) => {
      e.preventDefault();
      e.stopPropagation();
      audio.currentTime = Math.max(0, audio.currentTime - 10);
      updateProgress();
   };

   forwardBtn.onclick = (e) => {
      e.preventDefault();
      e.stopPropagation();
      const maxTime = audio.duration || Infinity;
      audio.currentTime = Math.min(maxTime, audio.currentTime + 10);
      updateProgress();
   };

   speedBtn.onclick = (e) => {
      e.preventDefault();
      e.stopPropagation();
      rateIndex = (rateIndex + 1) % PLAYBACK_RATES.length;
      const rate = PLAYBACK_RATES[rateIndex];
      audio.playbackRate = rate;
      speedBtn.textContent = `${rate}x`;
   };

   // Seek slider interaction
   const startScrubbing = () => {
      isScrubbing = true;
   };

   const onSliderInput = () => {
      const pct = parseFloat(seekSlider.value);
      seekSlider.style.setProperty('--seek-pct', `${pct}%`);
      if (audio.duration && !isNaN(audio.duration)) {
         const targetTime = (pct / 100) * audio.duration;
         timeCurrent.textContent = formatAudioTime(targetTime);
      }
   };

   const commitSeek = () => {
      const pct = parseFloat(seekSlider.value);
      if (audio.duration && !isNaN(audio.duration)) {
         audio.currentTime = (pct / 100) * audio.duration;
      }
      isScrubbing = false;
   };

   seekSlider.addEventListener('pointerdown', startScrubbing);
   seekSlider.addEventListener('touchstart', startScrubbing, { passive: true });
   seekSlider.addEventListener('mousedown', startScrubbing);

   seekSlider.addEventListener('input', onSliderInput);
   seekSlider.addEventListener('change', commitSeek);
   seekSlider.addEventListener('pointerup', commitSeek);
   seekSlider.addEventListener('touchend', commitSeek);

   // Audio element listeners
   audio.addEventListener('play', onPlay);
   audio.addEventListener('pause', onPause);
   audio.addEventListener('timeupdate', onTimeUpdate);
   audio.addEventListener('loadedmetadata', onLoadedMetadata);
   audio.addEventListener('durationchange', onLoadedMetadata);
   audio.addEventListener('ended', onEnded);

   // Initial sync
   updatePlayState(!audio.paused);
   updateDuration();
   updateProgress();

   const handle: CustomAudioPlayerHandle = {
      playerElement: wrapper,
      audio,
      destroy: () => {
         audio.removeEventListener('play', onPlay);
         audio.removeEventListener('pause', onPause);
         audio.removeEventListener('timeupdate', onTimeUpdate);
         audio.removeEventListener('loadedmetadata', onLoadedMetadata);
         audio.removeEventListener('durationchange', onLoadedMetadata);
         audio.removeEventListener('ended', onEnded);
         if (wrapper.parentNode) {
            wrapper.parentNode.insertBefore(audio, wrapper);
            wrapper.remove();
         }
         audio.controls = true;
         delete (audio as any).__customPlayerHandle;
      }
   };

   (audio as any).__customPlayerHandle = handle;
   return handle;
}
