export const MQTT_USERNAME = process.env.NEXT_PUBLIC_MQTT_USERNAME ?? "jcadmin";
export const MQTT_PASSWORD = process.env.NEXT_PUBLIC_MQTT_PASSWORD ?? "jcadmin@12345";

export function mqttAuthOptions() {
  const username = MQTT_USERNAME.trim();

  if (!username) {
    return {};
  }

  return {
    username,
    password: MQTT_PASSWORD,
  };
}
