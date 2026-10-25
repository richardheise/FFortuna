import { Stack } from 'expo-router';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { StatusBar } from 'expo-status-bar';
import { FinanceProvider } from '../src/demo/provider';

export default function RootLayout() {
  const stack = <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: '#F6F4EC' } }} />;
  return (
    <SafeAreaProvider>
      <StatusBar style="dark" />
      {process.env.EXPO_PUBLIC_FFORTUNA_MODE === 'capture-poc' ? stack : <FinanceProvider>{stack}</FinanceProvider>}
    </SafeAreaProvider>
  );
}
