// Browser bundles are public artifacts. Production MQTT credentials must never be
// supplied through NEXT_PUBLIC_* variables; authenticated device operations go
// through the backend instead. Direct browser MQTT tools therefore run without
// embedded credentials and only work with an intentionally anonymous test broker.
export const MQTT_USERNAME = "";
export const MQTT_PASSWORD = "";

export function mqttAuthOptions() {
  const username = MQTT_USERNAME.trim();

	if (!username || !MQTT_PASSWORD) {
		return {};
  }

  return {
    username,
    password: MQTT_PASSWORD,
  };
}
