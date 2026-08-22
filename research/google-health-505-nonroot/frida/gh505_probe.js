'use strict';

/*
 * Google Health 5.05 non-root, same-session research probe.
 *
 * This script never opens a BluetoothGatt, creates a StackPeer, or establishes
 * a second DTLS session. It only observes and, when explicitly requested from
 * the REPL/RPC API, reuses a live GoldenGateCommandHandler owned by Google Health.
 */

const HANDLER_CLASS = 'com.fitbit.goldengate.commands.GoldenGateCommandHandler';
const NOTIFICATION_CLASSES = [
  'com.fitbit.dncs.observers.AllAppNotificationListenerService',
  'com.fitbit.device.notifications.listener.service.IndependentNotificationListenerService'
];

const COAP = Object.freeze({ GET: 1, POST: 2, PUT: 3, DELETE: 4 });
const SETTINGS_READ_HAPTICS = '0A0101';
const SETTINGS_HAPTICS_LOW = '0A040A020802';
const SETTINGS_HAPTICS_HIGH = '0A040A020803';

const state = {
  installed: false,
  handler: null,
  handlerSource: null,
  sendOverload: null,
  commandCount: 0,
  notificationsSeen: 0,
  calls: [],
  retainedDisposables: []
};

function timestamp() {
  return new Date().toISOString();
}

function log(message) {
  console.log(`[fba-gh505 ${timestamp()}] ${message}`);
}

function errorText(error) {
  try {
    return `${error.name || 'Error'}: ${error.message || error}`;
  } catch (_) {
    return String(error);
  }
}

function bytesToHex(value, maxBytes) {
  if (value === null || value === undefined) return '';
  const limit = maxBytes || 160;
  const length = Number(value.length || 0);
  const count = Math.min(length, limit);
  let result = '';
  for (let index = 0; index < count; index += 1) {
    const byte = Number(value[index]) & 0xff;
    result += byte.toString(16).padStart(2, '0').toUpperCase();
  }
  if (length > count) result += `…(${length}B)`;
  return result;
}

function hexToSignedBytes(hex) {
  const normalized = String(hex).replace(/[^0-9a-fA-F]/g, '');
  if (normalized.length % 2 !== 0) throw new Error('hex payload must contain complete bytes');
  const values = [];
  for (let index = 0; index < normalized.length; index += 2) {
    const value = parseInt(normalized.slice(index, index + 2), 16);
    values.push(value > 127 ? value - 256 : value);
  }
  return values;
}

function methodName(value) {
  const number = Number(value) & 0xff;
  return ({ 1: 'GET', 2: 'POST', 3: 'PUT', 4: 'DELETE' })[number] || `METHOD(${number})`;
}

function boundedPush(array, value, limit) {
  array.push(value);
  while (array.length > limit) array.shift();
}

function javaClassName(value) {
  if (value === null || value === undefined) return 'null';
  try {
    if (value.$className) return String(value.$className);
  } catch (_) {}
  try {
    return String(value.getClass().getName());
  } catch (_) {
    return typeof value;
  }
}

function handlerReady(handler) {
  if (!handler) return false;
  try {
    if (!handler.isReady) return true;
    return Boolean(handler.isReady());
  } catch (_) {
    return false;
  }
}

function retainHandler(instance, source) {
  try {
    state.handler = Java.retain(instance);
    state.handlerSource = source;
    log(`captured ${HANDLER_CLASS} from ${source}; ready=${handlerReady(state.handler)}`);
  } catch (error) {
    log(`failed to retain handler: ${errorText(error)}`);
  }
}

function findSendOverload(Handler) {
  if (!Handler.sendGenericCommand) throw new Error('sendGenericCommand is absent');
  const matches = Handler.sendGenericCommand.overloads.filter((overload) => {
    const args = overload.argumentTypes.map((type) => String(type.className));
    return args.length === 3 && args[0] === 'java.lang.String' && args[1] === '[B' && args[2] === 'byte';
  });
  if (matches.length !== 1) {
    const rendered = Handler.sendGenericCommand.overloads
      .map((overload) => `(${overload.argumentTypes.map((type) => type.className).join(', ')})`)
      .join(', ');
    throw new Error(`expected one sendGenericCommand(String, byte[], byte), found ${matches.length}: ${rendered}`);
  }
  return matches[0];
}

function subscribeIfCold(result) {
  if (result === null || result === undefined) return 'void/null';
  const className = javaClassName(result);
  try {
    if (result.subscribe && result.subscribe.overloads) {
      const zeroArg = result.subscribe.overloads.find((overload) => overload.argumentTypes.length === 0);
      if (zeroArg) {
        const disposable = zeroArg.call(result);
        if (disposable) {
          boundedPush(state.retainedDisposables, Java.retain(disposable), 16);
        }
        return `${className}; subscribed`;
      }
    }
  } catch (error) {
    return `${className}; subscribe failed: ${errorText(error)}`;
  }
  return `${className}; returned without subscription`;
}

function sendCommandInsideJava(path, method, payloadHex) {
  if (!state.handler) throw new Error('no handler captured; change one Air setting in Google Health or run fbaFindHandlers()');
  if (!state.sendOverload) throw new Error('sendGenericCommand overload has not been resolved');
  if (!handlerReady(state.handler)) throw new Error('captured handler is not ready');

  const payload = Java.array('byte', hexToSignedBytes(payloadHex));
  const result = state.sendOverload.call(state.handler, String(path), payload, Number(method));
  const subscription = subscribeIfCold(result);
  log(`explicit replay ${methodName(method)} ${path} payload=${payloadHex || '<empty>'}; ${subscription}`);
  return {
    path: String(path),
    method: Number(method),
    methodName: methodName(method),
    payloadHex: String(payloadHex),
    resultClass: javaClassName(result),
    subscription
  };
}

function withJava(work) {
  return new Promise((resolve, reject) => {
    Java.perform(() => {
      try {
        resolve(work());
      } catch (error) {
        reject(error);
      }
    });
  });
}

function hookCommandHandler() {
  const Handler = Java.use(HANDLER_CLASS);
  state.sendOverload = findSendOverload(Handler);

  Handler.sendGenericCommand.overloads.forEach((overload, index) => {
    overload.implementation = function () {
      const args = Array.prototype.slice.call(arguments);
      const path = args.length > 0 ? String(args[0]) : '<unknown>';
      const payload = args.length > 1 ? args[1] : null;
      const method = args.length > 2 ? Number(args[2]) : -1;
      if (path.startsWith('/settings') || path.startsWith('/alarms')) {
        retainHandler(this, `${methodName(method)} ${path}`);
      }
      const result = overload.call(this, ...args);
      state.commandCount += 1;
      const record = {
        at: timestamp(),
        path,
        method,
        methodName: methodName(method),
        payloadHex: bytesToHex(payload),
        resultClass: javaClassName(result)
      };
      boundedPush(state.calls, record, 60);
      log(`#${state.commandCount} ${record.methodName} ${path} payload=${record.payloadHex || '<empty>'} -> ${record.resultClass}`);
      return result;
    };
    log(`hooked sendGenericCommand overload #${index}`);
  });

  ['sendSecureAppNotification', 'sendUnsecuredAppNotification'].forEach((methodNameValue) => {
    if (!Handler[methodNameValue]) return;
    Handler[methodNameValue].overloads.forEach((overload) => {
      overload.implementation = function () {
        const args = Array.prototype.slice.call(arguments);
        retainHandler(this, methodNameValue);
        log(`${methodNameValue} payload=${args.length ? bytesToHex(args[0]) : '<none>'}`);
        return overload.call(this, ...args);
      };
    });
    log(`hooked ${methodNameValue}`);
  });
}

function hookNotificationClass(className) {
  try {
    const Listener = Java.use(className);
    if (!Listener.onNotificationPosted) return;
    Listener.onNotificationPosted.overloads.forEach((overload) => {
      overload.implementation = function () {
        const args = Array.prototype.slice.call(arguments);
        let packageName = '<unknown>';
        try {
          if (args.length > 0 && args[0] && args[0].getPackageName) {
            packageName = String(args[0].getPackageName());
          }
        } catch (_) {}
        state.notificationsSeen += 1;
        log(`notification #${state.notificationsSeen} package=${packageName} via ${className}`);
        return overload.call(this, ...args);
      };
    });
    log(`hooked notification listener ${className}`);
  } catch (error) {
    log(`notification listener hook skipped for ${className}: ${errorText(error)}`);
  }
}

function install() {
  if (state.installed) return;
  Java.perform(() => {
    try {
      hookCommandHandler();
      NOTIFICATION_CLASSES.forEach(hookNotificationClass);
      state.installed = true;
      log('probe installed; no Bluetooth connection or device write has been initiated');
    } catch (error) {
      log(`probe installation failed: ${errorText(error)}`);
      throw error;
    }
  });
}

function statusObject() {
  return {
    installed: state.installed,
    handlerCaptured: Boolean(state.handler),
    handlerReady: handlerReady(state.handler),
    handlerSource: state.handlerSource,
    commandCount: state.commandCount,
    notificationsSeen: state.notificationsSeen,
    recentCalls: state.calls.slice(-12)
  };
}

function findHandlers() {
  return new Promise((resolve, reject) => {
    Java.perform(() => {
      let count = 0;
      let readyCount = 0;
      try {
        Java.choose(HANDLER_CLASS, {
          onMatch(instance) {
            count += 1;
            const ready = handlerReady(instance);
            if (ready) {
              readyCount += 1;
              retainHandler(instance, `Java.choose ready instance #${count}`);
            } else if (!state.handler) {
              retainHandler(instance, `Java.choose instance #${count}`);
            }
          },
          onComplete() {
            log(`Java.choose complete: total=${count}, ready=${readyCount}`);
            resolve({ total: count, ready: readyCount, captured: Boolean(state.handler) });
          }
        });
      } catch (error) {
        reject(error);
      }
    });
  });
}

function previewHighLowHigh() {
  return withJava(() => {
    const first = sendCommandInsideJava('/settings', COAP.POST, SETTINGS_HAPTICS_LOW);
    setTimeout(() => {
      Java.perform(() => {
        try {
          sendCommandInsideJava('/settings', COAP.POST, SETTINGS_HAPTICS_HIGH);
        } catch (error) {
          log(`restore HIGH failed: ${errorText(error)}`);
        }
      });
    }, 800);
    return {
      first,
      restoreScheduledMillis: 800,
      precondition: 'Google Health haptic intensity must already be HIGH'
    };
  });
}

install();

globalThis.fbaStatus = () => JSON.stringify(statusObject(), null, 2);
globalThis.fbaFindHandlers = () => findHandlers();
globalThis.fbaReadHaptics = () => withJava(() => sendCommandInsideJava('/settings', COAP.PUT, SETTINGS_READ_HAPTICS));
globalThis.fbaPreviewHighLowHigh = () => previewHighLowHigh();
globalThis.fbaSend = (path, method, payloadHex) => withJava(() => sendCommandInsideJava(path, method, payloadHex || ''));

rpc.exports = {
  status() {
    return statusObject();
  },
  findhandlers() {
    return findHandlers();
  },
  readhaptics() {
    return withJava(() => sendCommandInsideJava('/settings', COAP.PUT, SETTINGS_READ_HAPTICS));
  },
  previewhighlowhigh() {
    return previewHighLowHigh();
  },
  send(path, method, payloadHex) {
    return withJava(() => sendCommandInsideJava(path, method, payloadHex || ''));
  }
};
