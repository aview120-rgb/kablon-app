// Google AdMob + Google UMP consent. On the web build every call is a safe no-op.
import { Capacitor } from '@capacitor/core';
import { ADS } from './config.js';

let AdMob = null, ready = false, canRequest = false, interstitialLoaded = false, ageGroup = 'adult';
const native = () => Capacitor.isNativePlatform() && ADS.enabled;

function audience() {
  if (ageGroup === 'child') return { tagForChildDirectedTreatment: true, tagForUnderAgeOfConsent: true, maxAdContentRating: 'General' };
  if (ageGroup === 'teen') return { tagForChildDirectedTreatment: false, tagForUnderAgeOfConsent: true, maxAdContentRating: 'ParentalGuidance' };
  return { tagForChildDirectedTreatment: false, tagForUnderAgeOfConsent: false, maxAdContentRating: 'ParentalGuidance' };
}
const npa = () => ageGroup !== 'adult';

// Order required by Google: consent info -> consent form (if required) -> initialize -> ads.
export async function initAds(state) {
  ageGroup = state.profile.adult === true ? 'adult' : state.profile.adult === 'teen' ? 'teen' : state.profile.adult === 'child' ? 'child' : 'adult';
  if (!native()) { state.ads.consent = 'web'; return false; }
  try {
    ({ AdMob } = await import('@capacitor-community/admob'));
    const info = await AdMob.requestConsentInfo({ tagForUnderAgeOfConsent: audience().tagForUnderAgeOfConsent });
    let st = info;
    if (info.isConsentFormAvailable && info.status === 'REQUIRED') st = await AdMob.showConsentForm();
    state.ads.consent = String(st.status || 'unknown').toLowerCase();
    canRequest = st.canRequestAds !== false;
    await AdMob.initialize({ initializeForTesting: ADS.testMode, ...audience() });
    // The real banner height differs per phone (50–90dp): reserve exactly that much space for it.
    AdMob.addListener('bannerAdSizeChanged', (size) => {
      const hgt = Number(size?.height) || 0;
      if (hgt > 0) { document.documentElement.style.setProperty('--banner-real-h', hgt + 'px'); document.body.classList.add('has-banner'); }
      else document.body.classList.remove('has-banner');
    });
    ready = true;
    return canRequest;
  } catch (e) { console.warn('ads init failed', e); return false; }
}

export async function privacyOptionsRequired() {
  if (!native() || !AdMob) return false;
  try { const i = await AdMob.requestConsentInfo(); return i.privacyOptionsRequirementStatus === 'REQUIRED'; } catch { return false; }
}
export async function showPrivacyOptions() {
  if (!native() || !AdMob) return false;
  try { await AdMob.showPrivacyOptionsForm(); return true; } catch { return false; }
}

export async function showBanner() {
  if (!ready || !canRequest) return;
  try {
    await AdMob.showBanner({ adId: ADS.bannerId, adSize: 'ADAPTIVE_BANNER', position: 'BOTTOM_CENTER', margin: 0, isTesting: ADS.testMode, npa: npa() });
    document.body.classList.add('has-banner');
  } catch { /* no fill / offline */ }
}
export async function hideBanner() {
  document.body.classList.remove('has-banner');
  if (!ready) return; try { await AdMob.hideBanner(); } catch {}
}
export async function resumeBanner() {
  if (!ready || !canRequest) return;
  try { await AdMob.resumeBanner(); document.body.classList.add('has-banner'); } catch { showBanner(); }
}

async function loadInterstitial() {
  if (!ready || !canRequest || interstitialLoaded) return;
  try { await AdMob.prepareInterstitial({ adId: ADS.interstitialId, isTesting: ADS.testMode, npa: npa() }); interstitialLoaded = true; } catch {}
}
// Called only on the lesson-complete screen, never inside a lesson. Capped by ADS.interstitialEveryLessons.
export async function maybeInterstitial(state) {
  state.ads.lessonsSinceInterstitial += 1;
  if (!ready || !canRequest) return false;
  if (state.ads.lessonsSinceInterstitial < ADS.interstitialEveryLessons) { loadInterstitial(); return false; }
  await loadInterstitial();
  if (!interstitialLoaded) return false;
  try { await AdMob.showInterstitial(); interstitialLoaded = false; state.ads.lessonsSinceInterstitial = 0; loadInterstitial(); return true; } catch { return false; }
}

// Rewarded: user CHOOSES to watch (e.g. to get a streak freeze). Resolves true if reward earned.
export function rewardedAvailable() { return ready && canRequest; }
export async function showRewarded() {
  if (!ready || !canRequest) return false;
  try {
    await AdMob.prepareRewardVideoAd({ adId: ADS.rewardedId, isTesting: ADS.testMode, npa: npa() });
    const item = await AdMob.showRewardVideoAd();
    return !!item;
  } catch { return false; }
}
