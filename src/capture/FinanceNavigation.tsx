import { Pressable, Text, View } from 'react-native';
import { colors } from '../ui/common';

export type MainTab = 'home' | 'transactions' | 'review' | 'more';

export function TiqueMark() {
  return <View accessible={false} importantForAccessibility="no-hide-descendants" style={{ width: 26, height: 26, alignItems: 'center', justifyContent: 'center' }}>
    <View style={{ position: 'absolute', width: 19, height: 19, borderColor: colors.gold, borderWidth: 1 }} />
    <View style={{ position: 'absolute', width: 19, height: 19, borderColor: colors.gold, borderWidth: 1, transform: [{ rotate: '45deg' }] }} />
    <View style={{ width: 5, height: 5, backgroundColor: colors.gold, transform: [{ rotate: '45deg' }] }} />
  </View>;
}
function Icon({ tab, active }: { tab: MainTab; active: boolean }) {
  const color = active ? colors.gold : colors.muted;
  if(tab === 'more') return <View style={{ height: 22, flexDirection: 'row', alignItems: 'center', gap: 3 }}>{[1,2,3].map(n => <View key={n} style={{ width: 3, height: 3, backgroundColor: color }} />)}</View>;
  if(tab === 'transactions') return <View style={{ width: 20, height: 22, borderWidth: 1.5, borderColor: color, padding: 4, gap: 3 }}>{[1,2,3].map(n => <View key={n} style={{ height: 1.5, backgroundColor: color }} />)}</View>;
  if(tab === 'review') return <View style={{ width: 22, height: 19, borderWidth: 1.5, borderColor: color, justifyContent: 'center', alignItems: 'center' }}><Text style={{ color, fontSize: 13, lineHeight: 15 }}>✓</Text></View>;
  return <View style={{ width: 22, height: 23, alignItems: 'center', justifyContent: 'flex-end' }}><View style={{ position: 'absolute', top: 0, width: 14, height: 14, borderTopWidth: 1.5, borderLeftWidth: 1.5, borderColor: color, transform: [{ rotate: '45deg' }] }} /><View style={{ width: 17, height: 15, borderWidth: 1.5, borderColor: color, backgroundColor: colors.paper }} /></View>;
}

export function FinanceNavigation({ active, disabled, onTab, onNew }: { active: MainTab | 'new'; disabled: boolean; onTab: (tab: MainTab) => void; onNew: () => void }) {
  const items: Array<{ tab: MainTab; title: string }> = [{ tab: 'home', title: 'Início' },{ tab: 'transactions', title: 'Movimentos' },{ tab: 'review', title: 'Revisão' },{ tab: 'more', title: 'Mais' }];
  const item = ({ tab, title }: typeof items[number]) => <Pressable key={tab} accessibilityRole="tab" accessibilityState={{ selected: active === tab, disabled }} disabled={disabled} onPress={() => onTab(tab)} style={{ flex: 1, minHeight: 64, alignItems: 'center', justifyContent: 'center', gap: 7, paddingHorizontal: 2, opacity: disabled ? 0.55 : 1 }}>
    <Icon tab={tab} active={active === tab} /><Text style={{ color: active === tab ? colors.gold : colors.muted, fontSize: 11, textAlign: 'center' }}>{title}</Text>
  </Pressable>;
  return <View accessibilityRole="tablist" style={{ flexDirection: 'row', alignItems: 'center', borderTopWidth: 1, borderColor: colors.line, backgroundColor: colors.paper, paddingHorizontal: 8, paddingVertical: 8 }}>
    {items.slice(0,2).map(item)}
    <Pressable accessibilityRole="button" accessibilityLabel="Novo lançamento: receita ou despesa" disabled={disabled} onPress={onNew} style={{ flex: 1, minHeight: 64, alignItems: 'center', justifyContent: 'center', gap: 3, opacity: disabled ? 0.55 : 1 }}>
      <View style={{ width: 48, height: 48, borderRadius: 4, backgroundColor: colors.gold, alignItems: 'center', justifyContent: 'center' }}><Text style={{ color: colors.paper, fontSize: 32, lineHeight: 38 }}>+</Text></View><Text style={{ color: colors.gold, fontSize: 11 }}>Novo</Text>
    </Pressable>
    {items.slice(2).map(item)}
  </View>;
}
