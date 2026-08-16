@file:OptIn(ExperimentalWasmJsInterop::class)

package ru.bolid.testdpls.web

@JsFun("() => Date.now()")
external fun dateNow(): Double

@JsFun("() => (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws'")
external fun labWsUrl(): String

@JsFun("(key) => localStorage.getItem(key)")
external fun storageGet(key: String): String?

@JsFun("(key, value) => { localStorage.setItem(key, value); }")
external fun storageSet(key: String, value: String)

@JsFun("(key) => { localStorage.removeItem(key); }")
external fun storageRemove(key: String)

@JsFun(
    """(url, onOpen, onClose, onMessage) => {
      const socket = new WebSocket(url);
      const queue = [];
      socket._q = queue;
      socket.onopen = () => {
        while (queue.length > 0) {
          try { socket.send(queue.shift()); } catch (e) {}
        }
        onOpen();
      };
      socket.onclose = () => onClose();
      socket.onmessage = (event) => {
        if (typeof event.data === 'string') onMessage(event.data);
      };
      return socket;
    }""",
)
external fun openRawSocket(
    url: String,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onMessage: (String) -> Unit,
): JsAny

@JsFun(
    """(socket, data) => {
      if (socket.readyState === 1) {
        socket.send(data);
        return;
      }
      if (socket._q) socket._q.push(data);
    }""",
)
external fun socketSend(socket: JsAny, data: String)

@JsFun("(socket) => { socket.close(); }")
external fun socketClose(socket: JsAny)
