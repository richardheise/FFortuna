import { Text } from '../ui/Typography';
import { useMemo, useState } from 'react';
import { FlatList, Modal, Pressable,  View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { colors } from '../ui/common';
import notices from './notices.json';
import nativeNotices from './native-notices.json';

/** Small chunks keep long license texts readable without laying out the entire inventory at once. */
export function LegalNotices({ visible, onClose }: { visible: boolean; onClose: () => void }) {
  const [section, setSection] = useState<'appLicense' | 'thirdPartyText'>('appLicense');
  const chunks = useMemo(() => {
    const text = section === 'thirdPartyText' ? (process.env.EXPO_PUBLIC_FFORTUNA_MODE === 'capture-poc' ? nativeNotices.text : notices.thirdPartyText) : notices.appLicense;
    const lines = text.split('\n');
    const result: string[] = [];
    for (let start = 0; start < lines.length; start += 45) result.push(lines.slice(start, start + 45).join('\n'));
    return result;
  }, [section]);
  return <Modal visible={visible} animationType="slide" onRequestClose={onClose}>
    <SafeAreaView style={{ flex: 1, backgroundColor: colors.paper }}>
      <View style={{ padding: 18, gap: 10 }}>
        <Text style={{ color: colors.ink, fontSize: 23, fontWeight: '700' }}>Tique · Software livre</Text>
        <Text style={{ color: colors.muted, fontSize: 14 }}>Tique, a deusa grega da fortuna. Anteriormente chamado FFortuna. Código original sob AGPLv3, sem garantia. Bibliotecas e fontes mantêm suas próprias licenças.</Text>
        <View style={{ flexDirection: 'row', gap: 10 }}>
          {(['appLicense', 'thirdPartyText'] as const).map((item) => <Pressable key={item} accessibilityRole="button" accessibilityState={{ selected: section === item }} onPress={() => setSection(item)} style={{ padding: 12, minHeight: 48, borderRadius: 4, backgroundColor: section === item ? colors.gold : colors.pale }}>
            <Text style={{ color: section === item ? colors.paper : colors.ink }}>{item === 'appLicense' ? 'AGPLv3' : 'Bibliotecas'}</Text>
          </Pressable>)}
          <Pressable accessibilityRole="button" onPress={onClose} style={{ marginLeft: 'auto', padding: 12, minHeight: 48 }}><Text style={{ color: colors.gold, fontWeight: '700' }}>Fechar</Text></Pressable>
        </View>
      </View>
      <FlatList key={section} data={chunks} keyExtractor={(_, index) => String(index)} initialNumToRender={2} windowSize={5}
        renderItem={({ item }) => <Text selectable style={{ paddingHorizontal: 18, paddingBottom: 8, color: colors.ink, fontFamily: 'monospace', fontSize: 12, lineHeight: 18 }}>{item}</Text>} />
    </SafeAreaView>
  </Modal>;
}
