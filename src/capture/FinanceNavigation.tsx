import { Pressable, Text, View } from 'react-native';
import { colors } from '../ui/common';

export type MainTab = 'sources' | 'transactions' | 'home';

export function TiqueMark() {
  return <View accessible={false} importantForAccessibility="no-hide-descendants" style={{ width: 26, height: 26, alignItems: 'center', justifyContent: 'center' }}>
    <View style={{ position: 'absolute', width: 19, height: 19, borderColor: colors.gold, borderWidth: 1 }} />
    <View style={{ position: 'absolute', width: 19, height: 19, borderColor: colors.gold, borderWidth: 1, transform: [{ rotate: '45deg' }] }} />
    <View style={{ width: 5, height: 5, backgroundColor: colors.gold, transform: [{ rotate: '45deg' }] }} />
  </View>;
}
function Icon({ tab, active }: { tab: MainTab; active: boolean }) {
  const color = active ? colors.gold : colors.muted;
  if(tab === 'sources') return <View style={{ width: 22, height: 22, justifyContent: 'center' }}>
    <View style={{ position: 'absolute', top: 2, left: 5, width: 14, height: 16, borderWidth: 1.5, borderColor: color }} />
    <View style={{ width: 14, height: 16, borderWidth: 1.5, borderColor: color, backgroundColor: colors.paper }} />
  </View>;
  if(tab === 'transactions') return <View style={{ width: 20, height: 22, borderWidth: 1.5, borderColor: color, padding: 4, gap: 3 }}>{[1,2,3].map(n => <View key={n} style={{ height: 1.5, backgroundColor: color }} />)}</View>;
  return <View style={{ width: 22, height: 23, alignItems: 'center', justifyContent: 'flex-end' }}><View style={{ position: 'absolute', top: 0, width: 14, height: 14, borderTopWidth: 1.5, borderLeftWidth: 1.5, borderColor: color, transform: [{ rotate: '45deg' }] }} /><View style={{ width: 17, height: 15, borderWidth: 1.5, borderColor: color, backgroundColor: colors.paper }} /></View>;
}

export function FinanceNavigation({ active, disabled, onTab }: { active: MainTab | 'new'; disabled: boolean; onTab: (tab: MainTab) => void; onNew?: () => void }) {
  const items: Array<{ tab: MainTab; title: string }> = [{ tab: 'sources', title: 'Fontes' },{ tab: 'transactions', title: 'Movimentos' },{ tab: 'home', title: 'Análises' }];
  const item = ({ tab, title }: typeof items[number]) => <Pressable key={tab} accessibilityRole="tab" accessibilityLabel={title} accessibilityState={{ selected: active === tab, disabled }} disabled={disabled} onPress={() => onTab(tab)} style={{ flex: 1, minHeight: 64, alignItems: 'center', justifyContent: 'center', gap: 7, paddingHorizontal: 2, opacity: disabled ? 0.55 : 1 }}>
    <Icon tab={tab} active={active === tab} /><Text style={{ color: active === tab ? colors.gold : colors.muted, fontSize: 11, textAlign: 'center' }}>{title}</Text>
  </Pressable>;
  return <View accessibilityRole="tablist" style={{ flexDirection: 'row', alignItems: 'center', borderTopWidth: 1, borderColor: colors.line, backgroundColor: colors.paper, paddingHorizontal: 8, paddingVertical: 8 }}>
    {items.map(item)}
  </View>;
}
