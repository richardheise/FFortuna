const { withAndroidManifest, withAndroidStyles, withAppBuildGradle, withProjectBuildGradle, withMainActivity } = require('expo/config-plugins');
module.exports = function withCapturePoc(config) {
  config = withAndroidStyles(config, (mod) => {
    const theme = mod.modResults.resources.style.find(style => style.$.name === 'AppTheme');
    if (!theme) throw new Error('AppTheme missing; dark system UI cannot be configured.');
    theme.item ??= [];
    for (const [name, value] of Object.entries({ 'android:windowLightStatusBar': 'false', 'android:windowLightNavigationBar': 'false', 'android:windowBackground': '#080B10', colorAccent: '#FFB020' })) {
      theme.item = theme.item.filter(item => item.$.name !== name);
      theme.item.push({ $: { name }, _: value });
    }
    return mod;
  });
  config = withAndroidManifest(config, (mod) => {
    const application = mod.modResults.manifest.application[0].$;
    application['android:allowBackup'] = 'false';
    application['android:fullBackupContent'] = '@xml/ffortuna_backup_rules';
    application['android:dataExtractionRules'] = '@xml/ffortuna_data_extraction_rules';
    const activity = mod.modResults.manifest.application[0].activity.find(item => item.$['android:name'] === '.MainActivity');
    if (!activity) throw new Error('MainActivity missing; statement intake unavailable.');
    activity['intent-filter'] ??= [];
    for (const action of ['android.intent.action.SEND', 'android.intent.action.VIEW']) {
      activity['intent-filter'].push({
        action: [{ $: { 'android:name': action } }],
        category: [{ $: { 'android:name': 'android.intent.category.DEFAULT' } }],
        data: ['text/csv', 'text/comma-separated-values', 'text/plain', 'application/x-ofx', 'application/ofx', 'application/x-qfx', 'application/octet-stream'].map(mime => ({ $: { 'android:mimeType': mime, ...(action.endsWith('VIEW') ? { 'android:scheme': 'content' } : {}) } })),
      });
    }
    return mod;
  });
  config = withMainActivity(config, (mod) => {
    if (!mod.modResults.contents.includes('WindowManager.LayoutParams.FLAG_SECURE')) {
      const marker = 'super.onCreate(null)';
      if (!mod.modResults.contents.includes(marker)) throw new Error('MainActivity creation hook missing; secure window is required.');
      mod.modResults.contents = mod.modResults.contents.replace(marker, `${marker}\n    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)`);
    }
    if (!mod.modResults.contents.includes('StatementIntake.receive')) {
      mod.modResults.contents = mod.modResults.contents.replace('super.onCreate(null)', 'if (org.ffortuna.capture.StatementIntake.receive(intent)) setIntent(android.content.Intent(android.content.Intent.ACTION_MAIN))\n    super.onCreate(null)');
      const end = mod.modResults.contents.lastIndexOf('}');
      mod.modResults.contents = mod.modResults.contents.slice(0, end) + `
  override fun onNewIntent(intent: android.content.Intent) {
    val safeIntent = if (org.ffortuna.capture.StatementIntake.receive(intent)) android.content.Intent(android.content.Intent.ACTION_MAIN) else intent
    super.onNewIntent(safeIntent)
    setIntent(safeIntent)
  }
` + mod.modResults.contents.slice(end);
    }
    return mod;
  });
  config = withProjectBuildGradle(config, (mod) => {
    if (!mod.modResults.contents.includes('// FFortuna bounded native compilation')) {
      mod.modResults.contents += `
// FFortuna bounded native compilation (also covers library CMake/Ninja tasks).
subprojects { sub ->
  ['com.android.application', 'com.android.library'].each { pluginId ->
    sub.plugins.withId(pluginId) {
      sub.androidComponents.finalizeDsl { ext ->
        ext.defaultConfig.externalNativeBuild.cmake.arguments.addAll([
          '-DCMAKE_JOB_POOLS=ff_compile=16;ff_link=1',
          '-DCMAKE_JOB_POOL_COMPILE=ff_compile', '-DCMAKE_JOB_POOL_LINK=ff_link'
        ])
      }
    }
  }
}
`;
    }
    return mod;
  });
  return withAppBuildGradle(config, (mod) => {
    if (!mod.modResults.contents.includes('// FFortuna bounded app compilation')) {
      mod.modResults.contents += `
// FFortuna bounded app compilation, after the React Native plugin's DSL setup.
androidComponents.finalizeDsl { ext ->
  ext.defaultConfig.minSdkVersion = 26
  ext.defaultConfig.externalNativeBuild.cmake.arguments.addAll([
    '-DCMAKE_JOB_POOLS=ff_compile=16;ff_link=1',
    '-DCMAKE_JOB_POOL_COMPILE=ff_compile', '-DCMAKE_JOB_POOL_LINK=ff_link'
  ])
}
`;
    }
    if (!mod.modResults.contents.includes('// FFortuna local PoC signing')) {
      mod.modResults.contents += `
// FFortuna local PoC signing: private local key, never the shared template debug key.
def ffSigningFile = rootProject.file('../.local/keys/poc-signing.properties')
if (!ffSigningFile.exists()) throw new GradleException('Run npm run native:build to prepare local signing.')
def ffSigning = new Properties()
ffSigningFile.withInputStream { ffSigning.load(it) }
android.signingConfigs.create('ffortunaPoc') {
    storeFile rootProject.file('../.local/keys/ffortuna-poc.p12')
    storePassword ffSigning.getProperty('storePassword')
    keyAlias 'ffortuna-poc'
    keyPassword ffSigning.getProperty('storePassword')
}
android.buildTypes.release.signingConfig = android.signingConfigs.ffortunaPoc
`;
    }
    return mod;
  });
};
