/**
@author Oli Gray
Capacitor settings for the FrogLog Android app
@version 1.0

Version    Author    Comments
1.0        CL        Initial version
*/

import type { CapacitorConfig } from '@capacitor/cli';

// Loads the live site. Set FROGLOG_DEV_URL when building to load a dev server for layout work.
// Steam and Xbox sign-in always return to the live site, so test those on a live build.
const devUrl = process.env.FROGLOG_DEV_URL;

const config: CapacitorConfig = {
  appId: 'uk.co.froglog.app',
  appName: 'FrogLog',
  webDir: 'www',
  server: {
    url: devUrl || 'https://froglog.co.uk',
    cleartext: !!devUrl,
    // Any other host opens in the phone's browser, which would finish a sign-in outside the app.
    allowNavigation: [
      'froglog.co.uk',
      'api.froglog.co.uk',
      'steamcommunity.com',
      '*.steamcommunity.com',
      'login.live.com',
      '*.login.live.com',
      'login.microsoftonline.com',
      'account.live.com',
      'account.microsoft.com',
    ],
  },
  plugins: {
    PushNotifications: {
      presentationOptions: ['alert', 'sound'],
    },
  },
};

export default config;
