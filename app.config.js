module.exports = ({ config }) => {
  if (process.env.FFORTUNA_MODE !== 'capture-poc') return config;
  return {
    ...config,
    name: 'Tique',
    slug: 'ffortuna-capture-poc',
    scheme: 'ffortuna-capture-poc',
    version: '0.11.0',
    updates: { enabled: false },
    android: {
      ...config.android,
      package: 'org.ffortuna.capturepoc',
      versionCode: 14,
      allowBackup: false,
      blockedPermissions: [
        'android.permission.INTERNET', 'android.permission.SYSTEM_ALERT_WINDOW',
        'android.permission.READ_EXTERNAL_STORAGE', 'android.permission.WRITE_EXTERNAL_STORAGE',
      ],
    },
    plugins: [...config.plugins, './plugins/withCapturePoc'],
  };
};
