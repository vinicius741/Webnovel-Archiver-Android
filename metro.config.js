const { getDefaultConfig } = require('expo/metro-config');

const config = getDefaultConfig(__dirname);

config.resolver.extraNodeModules = {
  stream: require.resolve('stream-browserify'),
  events: require.resolve('events'),
  string_decoder: require.resolve('string_decoder'),
  buffer: require.resolve('buffer'),
  url: require.resolve('url'),
};

config.resolver.resolveRequest = (context, moduleName, platform) => {
  if (moduleName.startsWith('node:')) {
    const newModuleName = moduleName.replace('node:', '');
    return context.resolveRequest(context, newModuleName, platform);
  }
  return context.resolveRequest(context, moduleName, platform);
};

module.exports = config;