/*
 * SHINE extras — appended to native-shell.js on every trusted page.
 *
 * The SHINE website was written for the old Flutter app, which exposed
 * window.flutter_inappwebview.callHandler('hapticFeedback' | 'startSpeechRecognition' |
 * 'stopSpeechRecognition') and answered with nativeSpeechResult / nativeSpeechLevel /
 * nativeSpeechStopped / nativeSpeechError window events. This recreates that contract on top of
 * NativeApp, so the website works in this app without any changes.
 */
(function () {
  'use strict';
  if (!window.NativeApp || !window.NativeApp.isNative || window.flutter_inappwebview) return;

  var App = window.NativeApp;
  var listening = false;

  function fire(name, detail) {
    window.dispatchEvent(new CustomEvent(name, { detail: detail }));
  }

  App.on('speech', function (e) {
    if (!e) return;
    if (e.type === 'result') fire('nativeSpeechResult', { text: e.text, final: !!e.final });
    else if (e.type === 'level') fire('nativeSpeechLevel', { level: e.level });
    else if (e.type === 'stopped') { listening = false; fire('nativeSpeechStopped'); }
    else if (e.type === 'error') { listening = false; fire('nativeSpeechError', { message: e.message || 'Speech recognition failed.' }); }
  });

  var handlers = {
    hapticFeedback: function () {
      return App.haptic('medium').then(function () { return { ok: true }; });
    },

    // Like the old app: a second start while listening stops, and failures arrive as
    // nativeSpeechError events rather than rejected promises.
    startSpeechRecognition: function () {
      if (listening) return handlers.stopSpeechRecognition();
      return App.speech.start({ locale: 'en-GB', pauseFor: 4000 }).then(function () {
        listening = true;
        App.haptic('medium');
        return { ok: true };
      }, function (err) {
        listening = false;
        fire('nativeSpeechError', { message: (err && err.message) || 'Could not start speech recognition. Please try again.' });
        if (err && err.code === 'permission') App.permissions.openSettings().catch(function () {});
        return { ok: true };
      });
    },

    stopSpeechRecognition: function () {
      var was = listening;
      listening = false;
      return App.speech.stop().then(function () {
        if (!was) fire('nativeSpeechStopped');
        return { ok: true };
      }, function () { return { ok: true }; });
    }
  };

  window.flutter_inappwebview = {
    callHandler: function (name) {
      var handler = handlers[name];
      if (!handler) return Promise.reject(new Error('No native handler called ' + name));
      return handler.apply(null, Array.prototype.slice.call(arguments, 1));
    }
  };

  // Microphone buttons that aren't wired to native speech themselves (carried over from the old
  // app): dictate into the nearest text field instead of using the web view's microphone.
  var activeTextarea = null;
  var originalValue = '';
  var activeButton = null;

  function showRecordingState(button) {
    if (!button) return;
    button.dataset.nativeSpeechLabel = button.getAttribute('aria-label') || '';
    button.setAttribute('data-native-listening', 'true');
    button.setAttribute('aria-label', 'Stop voice entry');
    button.style.outline = '3px solid #fb7185';
    button.style.outlineOffset = '3px';
  }

  function clearRecordingState(button) {
    if (!button) return;
    var previousLabel = button.dataset.nativeSpeechLabel;
    button.removeAttribute('data-native-listening');
    button.removeAttribute('data-native-speech-label');
    button.style.outline = '';
    button.style.outlineOffset = '';
    button.style.filter = '';
    if (previousLabel) button.setAttribute('aria-label', previousLabel);
  }

  var editableSelector = 'textarea:not([disabled]), input[type="text"]:not([disabled]), input:not([type]):not([disabled])';

  function visibleControls(root) {
    return Array.prototype.filter.call((root && root.querySelectorAll(editableSelector)) || [], function (control) {
      var rect = control.getBoundingClientRect();
      return rect.width > 0 && rect.height > 0;
    });
  }

  function nearestControl(controls, button) {
    var b = button.getBoundingClientRect();
    var centre = b.top + b.height / 2;
    return controls.sort(function (first, second) {
      var f = first.getBoundingClientRect();
      var s = second.getBoundingClientRect();
      return Math.abs((f.top + f.height / 2) - centre) - Math.abs((s.top + s.height / 2) - centre);
    })[0] || null;
  }

  document.addEventListener('click', function (event) {
    var candidate = event.target && event.target.closest ? event.target.closest('button') : null;
    if (!candidate) return;
    var name = String(candidate.getAttribute('aria-label') || candidate.textContent || '').trim().toLowerCase();
    if (!/(microphone|speak|dictat)/.test(name)) return;
    if (candidate.matches('[data-audio-file-recording="true"]')) return;
    if (candidate.closest('[x-data*="shineSpeechTextarea"]')) return;

    if (candidate === activeButton && candidate.matches('[data-native-listening="true"]')) {
      event.preventDefault();
      event.stopImmediatePropagation();
      window.flutter_inappwebview.callHandler('stopSpeechRecognition');
      return;
    }

    var field = candidate.closest('[x-data]') || candidate.closest('[role="dialog"]') ||
      (candidate.parentElement && candidate.parentElement.parentElement);
    var textarea = nearestControl(visibleControls(field), candidate);
    if (!textarea) {
      var dialog = candidate.closest('[role="dialog"], [x-show]');
      textarea = nearestControl(visibleControls(dialog || document), candidate);
    }
    if (!textarea) return;

    event.preventDefault();
    event.stopImmediatePropagation();

    activeTextarea = textarea;
    originalValue = textarea.value.trimEnd();
    activeButton = candidate;
    showRecordingState(candidate);
    window.flutter_inappwebview.callHandler('startSpeechRecognition');
  }, true);

  window.addEventListener('nativeSpeechResult', function (event) {
    if (!activeTextarea) return;
    var text = String((event.detail && event.detail.text) || '').trim();
    if (!text) return;
    activeTextarea.value = originalValue ? originalValue + '\n' + text : text;
    activeTextarea.dispatchEvent(new Event('input', { bubbles: true }));
    activeTextarea.dispatchEvent(new Event('change', { bubbles: true }));
  });

  function finish(message) {
    if (!activeButton) return;
    document.querySelectorAll('[data-native-listening="true"]').forEach(clearRecordingState);
    activeTextarea = null;
    activeButton = null;
    originalValue = '';
    if (message) window.alert(message);
  }

  window.addEventListener('nativeSpeechStopped', function () { finish(''); });
  window.addEventListener('nativeSpeechError', function (event) {
    finish((event.detail && event.detail.message) || 'Android speech recognition failed.');
  });
})();
