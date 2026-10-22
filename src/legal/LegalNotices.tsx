import { useMemo, useState } from 'react';
import { FlatList, Modal, Pressable, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import notices from './notices.json';

/** Small chunks keep long license texts readable without laying out the entire inventory at once. */
export function LegalNotices({ visible, onClose }: { visible: boolean; onClose: () => void }) {
  const [section, setSection] = useState<'appLicense' | 'thirdPartyText'>('appLicense');
  const chunks = useMemo(() => {
    const lines = notices[section].split('\n');
    const result: string[] = [];
    for (let start = 0; start < lines.length; start += 45) result.push(lines.slice(start, start + 45).join('\n'));
    return result;
  }, [section]);
  return <Modal visible={visible} animationType="slide" onRequestClose={onClose}>
    <SafeAreaView style={{ flex: 1, backgroundColor: '#F6F4EC' }}>
      <View style={{ padding: 18, gap: 10 }}>
        <Text style={{ color: '#19382C', fontSize: 23, fontWeight: '700' }}>FFortuna · Software livre</Text>
        <Text style={{ color: '#65766D', fontSize: 14 }}>F de free; Fortuna, a deusa romana. Código original sob AGPLv3, sem garantia. Bibliotecas e fontes mantêm suas próprias licenças.</Text>
        <View style={{ flexDirection: 'row', gap: 10 }}>
          {(['appLicense', 'thirdPartyText'] as const).map((item) => <Pressable key={item} accessibilityRole="button" accessibilityState={{ selected: section === item }} onPress={() => setSection(item)} style={{ padding: 12, minHeight: 44, borderRadius: 10, backgroundColor: section === item ? '#174C38' : '#E5EFE7' }}>
            <Text style={{ color: section === item ? '#FFF' : '#19382C' }}>{item === 'appLicense' ? 'AGPLv3' : 'Bibliotecas'}</Text>
          </Pressable>)}
          <Pressable accessibilityRole="button" onPress={onClose} style={{ marginLeft: 'auto', padding: 12, minHeight: 44 }}><Text style={{ color: '#174C38', fontWeight: '700' }}>Fechar</Text></Pressable>
        </View>
      </View>
      <FlatList key={section} data={chunks} keyExtractor={(_, index) => String(index)} initialNumToRender={2} windowSize={5}
        renderItem={({ item }) => <Text selectable style={{ paddingHorizontal: 18, paddingBottom: 8, color: '#19382C', fontFamily: 'monospace', fontSize: 12, lineHeight: 18 }}>{item}</Text>} />
    </SafeAreaView>
  </Modal>;
}
