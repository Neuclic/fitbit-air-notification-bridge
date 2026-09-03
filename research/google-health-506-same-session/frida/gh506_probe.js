'use strict';

/*
 * Google Health 5.06.1 non-root, same-session research probe.
 *
 * This script never opens a BluetoothGatt, creates a StackPeer, or establishes
 * a second DTLS session. It only observes and, when explicitly requested,
 * reuses a live GoldenGateCommandHandler owned by Google Health.
 */

const HANDLER_CLASS = 'com.fitbit.goldengate.commands.GoldenGateCommandHandler';
const SENDER_CLASS = 'com.fitbit.goldengate.notifications.NotificationSenderCommandHandler';
const NOTIFICATION_CLASSES = [
  'com.fitbit.dncs.observers.AllAppNotificationListenerService',
  'com.fitbit.device.notifications.listener.service.IndependentNotificationListenerService'
];

const COAP = Object.freeze({ GET: 1, POST: 2, PUT: 3, DELETE: 4 });
const SETTINGS_READ_HAPTICS = '0A0101';
const SETTINGS_HAPTICS_LOW = '0A040A020802';
const SETTINGS_HAPTICS_HIGH = '0A040A020803';
const SWITCHBOARD_CREATE_PATH = 'sb/records';
const SWITCHBOARD_DELETE_PATH = 'sb/delete-records';
const RECORD_VERSION = '0.4.10';
const DEFAULT_TTL_SECONDS = 60;
const MOBILE_ID_NAMESPACE = 0x80000000;
const MAX_LOCAL_ID = 0x3fffffff;
const EVENT_SHOW = 0x30;
const DEFAULT_APP_COLOR = 0xffb0b0b0;

const state = {
  installed: false,
  handler: null,
  handlerSource: null,
  sendOverload: null,
  sender: null,
  commandCount: 0,
  notificationsSeen: 0,
  switchboardCreates: 0,
  calls: [],
  retainedDisposables: []
};

function timestamp() {
  return new Date().toISOString();
}

function log(message) {
  console.log(`[fba-gh506 ${timestamp()}] ${message}`);
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

function retainSender(instance, source) {
  try {
    state.sender = Java.retain(instance);
    log(`captured ${SENDER_CLASS} from ${source}`);
  } catch (error) {
    log(`failed to retain notification sender: ${errorText(error)}`);
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

function writeVarint(bytes, value) {
  let remaining = value >>> 0;
  while (true) {
    if ((remaining & ~0x7f) === 0) {
      bytes.push(remaining);
      return;
    }
    bytes.push((remaining & 0x7f) | 0x80);
    remaining >>>= 7;
  }
}

function writeTag(bytes, fieldNumber, wireType) {
  writeVarint(bytes, (fieldNumber << 3) | wireType);
}

function writeUint32(bytes, fieldNumber, value) {
  writeTag(bytes, fieldNumber, 0);
  writeVarint(bytes, value >>> 0);
}

function writeUint64(bytes, fieldNumber, value) {
  writeTag(bytes, fieldNumber, 0);
  let remaining = value;
  while (true) {
    if (remaining < 0x80) {
      bytes.push(remaining);
      return;
    }
    bytes.push((remaining & 0x7f) | 0x80);
    remaining = Math.floor(remaining / 128);
  }
}

function writeFixed32(bytes, fieldNumber, value) {
  writeTag(bytes, fieldNumber, 5);
  const raw = value >>> 0;
  bytes.push(raw & 0xff, (raw >>> 8) & 0xff, (raw >>> 16) & 0xff, (raw >>> 24) & 0xff);
}

function writeBytes(bytes, fieldNumber, nested) {
  writeTag(bytes, fieldNumber, 2);
  writeVarint(bytes, nested.length);
  for (let index = 0; index < nested.length; index += 1) bytes.push(nested[index]);
}

function writeString(bytes, fieldNumber, value) {
  const encoded = [];
  const buffer = unescape(encodeURIComponent(String(value)));
  for (let index = 0; index < buffer.length; index += 1) encoded.push(buffer.charCodeAt(index) & 0xff);
  writeBytes(bytes, fieldNumber, encoded);
}

function mobileId(localId) {
  return (MOBILE_ID_NAMESPACE | (localId & MAX_LOCAL_ID)) >>> 0;
}

function encodeSwitchboardNotification(options) {
  const idSeed = Number(options.idSeed);
  const timestamp = Number(options.timestampEpochSeconds);
  const expires = Number(options.expiresAtEpochSeconds || timestamp + DEFAULT_TTL_SECONDS);
  const rootId = mobileId(idSeed);
  const popupExpressionId = mobileId(idSeed + 1);
  const schedulerConfigurationId = mobileId(idSeed + 2);
  const ttlConfigurationId = mobileId(idSeed + 3);
  const commonExpressionId = mobileId(idSeed + 4);

  const schedulerEvent = [];
  writeUint32(schedulerEvent, 1, EVENT_SHOW);
  writeFixed32(schedulerEvent, 2, popupExpressionId);
  const scheduler = [];
  writeUint32(scheduler, 1, 0);
  writeBytes(scheduler, 2, schedulerEvent);
  const schedulerConfiguration = [];
  writeFixed32(schedulerConfiguration, 1, schedulerConfigurationId);
  writeBytes(schedulerConfiguration, 32, scheduler);

  const ttl = [];
  writeUint32(ttl, 1, 2);
  writeUint64(ttl, 3, expires);
  const ttlConfiguration = [];
  writeFixed32(ttlConfiguration, 1, ttlConfigurationId);
  writeBytes(ttlConfiguration, 33, ttl);

  const common = [];
  writeString(common, 1, options.appId);
  writeUint32(common, 2, DEFAULT_APP_COLOR);
  if (options.appName) writeString(common, 4, options.appName);
  if (options.title) writeString(common, 5, options.title);
  if (options.content) writeString(common, 6, options.content);
  writeUint64(common, 7, timestamp);
  const commonExpression = [];
  writeFixed32(commonExpression, 1, commonExpressionId);
  writeBytes(commonExpression, 36, common);

  const popupTemplate = [];
  writeUint32(popupTemplate, 1, commonExpressionId);
  const popup = [];
  writeBytes(popup, 4, popupTemplate);
  const popupExpression = [];
  writeFixed32(popupExpression, 1, popupExpressionId);
  writeBytes(popupExpression, 34, popup);

  const record = [];
  writeFixed32(record, 1, rootId);
  writeString(record, 2, RECORD_VERSION);
  writeBytes(record, 3, schedulerConfiguration);
  writeBytes(record, 3, ttlConfiguration);
  writeBytes(record, 4, commonExpression);
  writeBytes(record, 4, popupExpression);

  const deleteCommand = [];
  writeUint32(deleteCommand, 1, rootId);

  return {
    rootId,
    unsignedRootId: rootId >>> 0,
    recordHex: bytesToHex(record, record.length),
    deleteHex: bytesToHex(deleteCommand, deleteCommand.length),
    expiresAtEpochSeconds: expires
  };
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
      if (
        path.indexOf('settings') >= 0 ||
        path.indexOf('alarms') >= 0 ||
        path.indexOf('sb/') >= 0
      ) {
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

  if (Handler.writeMobileData) {
    Handler.writeMobileData.overloads.forEach((overload) => {
      overload.implementation = function () {
        const args = Array.prototype.slice.call(arguments);
        retainHandler(this, 'writeMobileData');
        const protocol = args.length > 1 ? Number(args[1]) : -1;
        log(`writeMobileData protocol=0x${(protocol >>> 0).toString(16)} argc=${args.length}`);
        return overload.call(this, ...args);
      };
    });
    log('hooked writeMobileData');
  }

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

function hookNotificationSender() {
  try {
    const Sender = Java.use(SENDER_CLASS);
    if (!Sender.send) return;
    Sender.send.overloads.forEach((overload) => {
      overload.implementation = function () {
        retainSender(this, 'send');
        log(`${SENDER_CLASS}.send argc=${arguments.length}`);
        return overload.call(this, ...arguments);
      };
    });
    log(`hooked ${SENDER_CLASS}.send`);
  } catch (error) {
    log(`notification sender hook skipped: ${errorText(error)}`);
  }
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
      hookNotificationSender();
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
    senderCaptured: Boolean(state.sender),
    commandCount: state.commandCount,
    notificationsSeen: state.notificationsSeen,
    switchboardCreates: state.switchboardCreates,
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

function switchboardGet() {
  return withJava(() => {
    const create = sendCommandInsideJava(SWITCHBOARD_CREATE_PATH, COAP.GET, '');
    const remove = sendCommandInsideJava(SWITCHBOARD_DELETE_PATH, COAP.GET, '');
    return { create, remove, note: 'read-only GETs; a 4.05 is useful path evidence' };
  });
}

function switchboardCreateOnce() {
  return withJava(() => {
    if (state.switchboardCreates !== 0) {
      throw new Error('this attach already created one Switchboard record; re-attach to try again');
    }
    const nowSeconds = Math.floor(Date.now() / 1000);
    const encoded = encodeSwitchboardNotification({
      idSeed: (Date.now() % 0x0fffffff) + 1,
      appId: 'com.fitbit.FitbitMobile',
      appName: 'Air same-session probe',
      title: 'Same-session probe',
      content: 'Google Health 5.06.1 Switchboard via live handler',
      timestampEpochSeconds: nowSeconds
    });
    state.switchboardCreates += 1;
    const created = sendCommandInsideJava(SWITCHBOARD_CREATE_PATH, COAP.POST, encoded.recordHex);
    setTimeout(() => {
      Java.perform(() => {
        try {
          sendCommandInsideJava(SWITCHBOARD_DELETE_PATH, COAP.POST, encoded.deleteHex);
        } catch (error) {
          log(`Switchboard delete failed: ${errorText(error)}`);
        }
      });
    }, 2000);
    return {
      created,
      encoded,
      deleteScheduledMillis: 2000,
      ttlSeconds: DEFAULT_TTL_SECONDS
    };
  });
}

install();

globalThis.fbaStatus = () => JSON.stringify(statusObject(), null, 2);
globalThis.fbaFindHandlers = () => findHandlers();
globalThis.fbaReadHaptics = () => withJava(() => sendCommandInsideJava('/settings', COAP.PUT, SETTINGS_READ_HAPTICS));
globalThis.fbaPreviewHighLowHigh = () => previewHighLowHigh();
globalThis.fbaSwitchboardGet = () => switchboardGet();
globalThis.fbaSwitchboardCreateOnce = () => switchboardCreateOnce();
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
  switchboardget() {
    return switchboardGet();
  },
  switchboardcreateonce() {
    return switchboardCreateOnce();
  },
  send(path, method, payloadHex) {
    return withJava(() => sendCommandInsideJava(path, method, payloadHex || ''));
  }
};
