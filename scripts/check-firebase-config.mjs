import fs from "node:fs";

try {
  const config = JSON.parse(fs.readFileSync("app/google-services.json", "utf8"));
  const project = config.project_info;
  const client = config.client?.find(c => c.client_info?.android_client_info?.package_name === "app.aino.mobile");
  if (!project?.project_id || !/^\d+$/.test(project.project_number ?? "") ||
      !client?.client_info?.mobilesdk_app_id || !client.api_key?.some(k => k.current_key)) {
    throw new Error("Invalid configuration");
  }
  console.log("Firebase configuration verified for Android application (identifiers redacted).");
} catch {
  console.error("Firebase release configuration missing, malformed, or not for app.aino.mobile. Check ANDROID_GOOGLE_SERVICES_JSON and the server Firebase project.");
  process.exitCode = 1;
}
