import { useFonts } from 'expo-font';
import { Stack } from 'expo-router';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { StatusBar } from 'expo-status-bar';
import { colors } from '../src/ui/common';
import { FinanceProvider } from '../src/demo/provider';

export default function RootLayout() {
  const [fontsLoaded, fontError] = useFonts({
    'SpaceGrotesk-Regular': require('../assets/fonts/SpaceGrotesk-Regular.ttf'),
    'SpaceGrotesk-Medium': require('../assets/fonts/SpaceGrotesk-Medium.ttf'),
    'SpaceGrotesk-Bold': require('../assets/fonts/SpaceGrotesk-Bold.ttf'),
  });
  if (!fontsLoaded && !fontError) return null;

  const stack = <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: colors.paper } }} />;
  return (
    <SafeAreaProvider>
      <StatusBar style="light" />
      {process.env.EXPO_PUBLIC_FFORTUNA_MODE === 'capture-poc' ? stack : <FinanceProvider>{stack}</FinanceProvider>}
    </SafeAreaProvider>
  );
}
