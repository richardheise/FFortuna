import { Stack } from 'expo-router';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { StatusBar } from 'expo-status-bar';
import { FinanceProvider } from '../src/demo/provider';

export default function RootLayout() {
  return (
    <SafeAreaProvider>
      <StatusBar style="dark" />
      <FinanceProvider>
        <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: '#F6F4EC' } }} />
      </FinanceProvider>
    </SafeAreaProvider>
  );
}
