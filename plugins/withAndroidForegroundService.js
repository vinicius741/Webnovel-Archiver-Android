const { withAndroidManifest } = require('@expo/config-plugins');

function withAndroidForegroundService(config) {
  return withAndroidManifest(config, async (config) => {
    const androidManifest = config.modResults.manifest;
    
    if (!androidManifest.application || !androidManifest.application[0]) {
      return config;
    }

    const mainApplication = androidManifest.application[0];

    if (!mainApplication.service) {
      mainApplication.service = [];
    }

    let notifeeServiceFound = false;

    mainApplication.service.forEach((service) => {
      if (service['$']['android:name'] === 'app.notifee.core.ForegroundService') {
        service['$']['android:foregroundServiceType'] = 'dataSync';
        notifeeServiceFound = true;
      }
    });

    if (!notifeeServiceFound) {
      mainApplication.service.push({
        $: {
          'android:name': 'app.notifee.core.ForegroundService',
          'android:foregroundServiceType': 'dataSync',
        },
      });
    }

    return config;
  });
}

module.exports = withAndroidForegroundService;