import { Aedes } from "aedes";
import fs from "node:fs";
import http from "node:http";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import websocketStream from "websocket-stream";

loadRepoEnv();

const tcpPort = Number(process.env.MQTT_PORT ?? 1883);
const wsPort = Number(process.env.MQTT_WS_PORT ?? 9001);
const broker = await Aedes.createBroker({
  maxClientsIdLength: 128,
});

function loadRepoEnv() {
  const envPath = path.resolve(process.cwd(), "..", ".env");
  if (!fs.existsSync(envPath)) {
    return;
  }
  for (const line of fs.readFileSync(envPath, "utf8").split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) {
      continue;
    }
    const separator = trimmed.indexOf("=");
    if (separator <= 0) {
      continue;
    }
    const key = trimmed.slice(0, separator).trim();
    const value = trimmed.slice(separator + 1).trim();
    if (!(key in process.env)) {
      process.env[key] = value;
    }
  }
}

function localIpv4Addresses() {
  return Object.values(os.networkInterfaces())
    .flat()
    .filter((item) => item && item.family === "IPv4" && !item.internal)
    .map((item) => item.address);
}

function payloadText(payload) {
  const text = Buffer.from(payload ?? []).toString("utf8").trim();
  return text && /^[\u0009\u000a\u000d\u0020-\u007e\u4e00-\u9fff{}[\]":,._+\-/\\\dA-Za-z]+$/.test(text)
    ? text
    : "";
}

function payloadSummary(payload) {
  const buffer = Buffer.from(payload ?? []);
  const text = payloadText(buffer);
  const hex = buffer.toString("hex").toUpperCase();
  if (text && hex) {
    return `${buffer.length} bytes text=${JSON.stringify(text)} hex=${hex}`;
  }
  if (hex) {
    return `${buffer.length} bytes hex=${hex}`;
  }
  return "0 bytes";
}

broker.on("client", (client) => {
  console.log(`[mqtt] client connected: ${client?.id ?? "unknown"} from ${client?.conn?.remoteAddress ?? "unknown"}`);
});

broker.on("clientDisconnect", (client) => {
  console.log(`[mqtt] client disconnected: ${client?.id ?? "unknown"}`);
});

broker.on("subscribe", (subscriptions, client) => {
  const topics = subscriptions.map((item) => item.topic).join(", ");
  console.log(`[mqtt] ${client?.id ?? "unknown"} subscribed: ${topics}`);
});

broker.on("publish", (packet, client) => {
  if (packet.topic.startsWith("$SYS")) {
    return;
  }
  const source = client?.id ?? "broker";
  console.log(`[mqtt] ${source} -> ${packet.topic} (${payloadSummary(packet.payload)})`);
});

broker.on("clientError", (client, error) => {
  console.log(`[mqtt] client error: ${client?.id ?? "unknown"} ${error.message}`);
});

broker.on("connectionError", (client, error) => {
  console.log(`[mqtt] connection error: ${client?.id ?? "unknown"} ${error.message}`);
});

const tcpServer = net.createServer(broker.handle);
const wsHttpServer = http.createServer();
websocketStream.createServer({ server: wsHttpServer }, broker.handle);

tcpServer.listen(tcpPort, "0.0.0.0", () => {
  console.log(`[mqtt] TCP broker listening on 0.0.0.0:${tcpPort}`);
  console.log(`[mqtt] Local IPv4 addresses: ${localIpv4Addresses().join(", ") || "none"}`);
});

wsHttpServer.listen(wsPort, "0.0.0.0", () => {
  console.log(`[mqtt] WebSocket broker listening on ws://0.0.0.0:${wsPort}`);
});

function shutdown(signal) {
  console.log(`[mqtt] shutting down (${signal})`);
  wsHttpServer.close(() => undefined);
  tcpServer.close(() => undefined);
  broker.close(() => process.exit(0));
}

process.on("SIGINT", () => shutdown("SIGINT"));
process.on("SIGTERM", () => shutdown("SIGTERM"));
