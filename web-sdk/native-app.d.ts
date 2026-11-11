/**
 * Types for `window.NativeApp`, which the iOS / Android app injects into your site.
 * Copy this file into your web project (or reference it) for autocomplete.
 *
 *   if (window.NativeApp?.isNative) NativeApp.haptic('success');
 */

type HapticType = 'light' | 'medium' | 'heavy' | 'soft' | 'rigid' | 'success' | 'warning' | 'error' | 'selection';
type PermissionName = 'camera' | 'microphone' | 'location' | 'notifications' | 'photos';
type PermissionStatus = 'granted' | 'denied' | 'prompt' | 'prompt-with-rationale' | 'limited';

interface NativePushPayload {
  id: string | null;
  title: string | null;
  body: string | null;
  /** "Additional data" set in OneSignal */
  data: Record<string, unknown>;
  launchUrl: string | null;
}

interface NativePushClick extends NativePushPayload {
  actionId: string | null;
  /** data.url, the action URL or the launch URL — already opened by the app */
  url: string | null;
}

interface NativePushState {
  provider: 'onesignal' | 'none';
  enabled: boolean;
  permission: PermissionStatus;
  optedIn?: boolean;
  subscriptionId?: string | null;
  token?: string | null;
  externalId: string | null;
}

interface NativeAppEvents {
  ready: NativeApp;
  resume: undefined;
  pause: undefined;
  'push:click': NativePushClick;
  'push:received': NativePushPayload;
  'push:permission': { granted: boolean };
  network: { connected: boolean; connectionType: 'wifi' | 'cellular' | 'none' | 'unknown' };
  tab: { index: number; url: string };
}

interface NativeApp {
  readonly isNative: true;
  readonly platform: 'ios' | 'android';
  readonly version: string;

  ready(fn?: (app: NativeApp) => void): Promise<NativeApp>;
  on<K extends keyof NativeAppEvents>(event: K, cb: (detail: NativeAppEvents[K]) => void): () => void;

  haptic(type?: HapticType): Promise<void>;
  vibrate(ms?: number): Promise<void>;
  share(opts: { title?: string; text?: string; url?: string; dialogTitle?: string }): Promise<{ activityType?: string }>;
  openUrl(url: string, opts?: { mode?: 'inApp' | 'browser' }): Promise<void>;
  download(url: string, filename?: string): Promise<{ saved: boolean; path: string }>;
  copy(text: string): Promise<void>;
  paste(): Promise<string>;
  reload(): Promise<void>;
  keepAwake(on?: boolean): Promise<void>;
  pullToRefresh(enabled?: boolean): Promise<void>;

  device: {
    info(): Promise<{
      platform: string; model: string; operatingSystem: string; osVersion: string; manufacturer: string;
      isVirtual: boolean; webViewVersion: string; appVersion?: string; appBuild?: string; deviceId: string;
    }>;
    battery(): Promise<{ batteryLevel?: number; isCharging?: boolean }>;
    language(): Promise<string>;
  };

  network: { status(): Promise<NativeAppEvents['network']> };

  statusBar: {
    /** 'dark' = dark icons for light backgrounds, 'light' = light icons for dark backgrounds */
    setStyle(style: 'dark' | 'light'): Promise<void>;
    hide(): Promise<void>;
    show(): Promise<void>;
  };

  permissions: {
    check(): Promise<Record<PermissionName, PermissionStatus>>;
    request(names: PermissionName | PermissionName[]): Promise<Partial<Record<PermissionName, PermissionStatus>>>;
    openSettings(): Promise<void>;
  };

  notifications: {
    requestPermission(): Promise<{ granted: boolean }>;
    getState(): Promise<NativePushState>;
    /** Link this device to your user so you can target them from your server (OneSignal external_id). */
    login(externalId: string | number): Promise<void>;
    logout(): Promise<void>;
    addTags(tags: Record<string, string | number | boolean>): Promise<void>;
    removeTags(keys: string | string[]): Promise<void>;
    setEmail(email: string): Promise<void>;
    optIn(): Promise<void>;
    optOut(): Promise<void>;
    clearAll(): Promise<void>;
    onClick(cb: (n: NativePushClick) => void): () => void;
    onReceived(cb: (n: NativePushPayload) => void): () => void;
  };

  /**
   * Number on the app icon (e.g. outstanding to-dos). `applied: false` means notifications
   * aren't allowed yet — call notifications.requestPermission() first.
   * Also available as navigator.setAppBadge(n) / clearAppBadge(), or <meta name="app-badge" content="3">.
   */
  badge: {
    set(count: number, opts?: { /** Android: quiet notification text, "{count}" is replaced */ text?: string }): Promise<{ count: number; applied: boolean; reason?: 'permission' }>;
    clear(): Promise<{ count: number; applied: boolean }>;
    get(): Promise<number>;
  };

  tabs: {
    select(index: number): Promise<void>;
    setBadge(index: number, text: string | number | null): Promise<void>;
    show(visible?: boolean): Promise<void>;
  };
}

interface Window {
  NativeApp?: NativeApp;
}

interface WindowEventMap {
  'nativeapp:ready': CustomEvent<NativeApp>;
  'nativeapp:push:click': CustomEvent<NativePushClick>;
  'nativeapp:push:received': CustomEvent<NativePushPayload>;
}
