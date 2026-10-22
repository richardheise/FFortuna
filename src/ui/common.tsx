import React from 'react';
import { Pressable, ScrollView, StyleSheet, Text, TextInput, View, type TextInputProps, type ViewStyle } from 'react-native';

export const colors = { paper: '#F6F4EC', card: '#FFFEFA', ink: '#19382C', muted: '#65766D', green: '#174C38', pale: '#E5EFE7', line: '#DCE3D9', yellow: '#F5E8BB', red: '#9C3B32', white: '#FFFFFF' };
export const styles = StyleSheet.create({
  page: { flex: 1, backgroundColor: colors.paper }, scroll: { paddingHorizontal: 18, paddingBottom: 32, gap: 14 },
  card: { backgroundColor: colors.card, borderColor: colors.line, borderWidth: 1, borderRadius: 18, padding: 16, gap: 10 },
  title: { color: colors.ink, fontSize: 24, fontWeight: '700' }, heading: { color: colors.ink, fontSize: 18, fontWeight: '700' }, body: { color: colors.ink, fontSize: 15, lineHeight: 21 }, small: { color: colors.muted, fontSize: 13, lineHeight: 18 }, label: { color: colors.ink, fontSize: 13, fontWeight: '600' },
  input: { minHeight: 48, borderRadius: 12, borderWidth: 1, borderColor: colors.line, backgroundColor: colors.white, paddingHorizontal: 13, paddingVertical: 10, color: colors.ink, fontSize: 16 },
  button: { minHeight: 48, paddingHorizontal: 16, borderRadius: 13, backgroundColor: colors.green, alignItems: 'center', justifyContent: 'center' }, buttonText: { color: colors.white, fontWeight: '700', fontSize: 15 }, secondary: { backgroundColor: colors.pale }, secondaryText: { color: colors.green },
  row: { flexDirection: 'row', alignItems: 'center', gap: 10 }, wrap: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 }, badge: { borderRadius: 99, backgroundColor: colors.pale, paddingHorizontal: 10, paddingVertical: 6 }, divider: { height: 1, backgroundColor: colors.line },
  error: { backgroundColor: '#FCE9E6', borderRadius: 12, padding: 12, color: colors.red, fontSize: 14 }, warning: { backgroundColor: colors.yellow, borderRadius: 12, padding: 12, color: colors.ink, fontSize: 13, lineHeight: 19 },
});

export function Card({ children, style }: { children: React.ReactNode; style?: ViewStyle }) { return <View style={[styles.card, style]}>{children}</View>; }
export function Label({ children }: { children: React.ReactNode }) { return <Text style={styles.label}>{children}</Text>; }
export function Field({ label, ...props }: TextInputProps & { label: string }) { return <View style={{ gap: 6 }}><Label>{label}</Label><TextInput placeholderTextColor="#87938C" {...props} accessibilityLabel={label} style={[styles.input, props.multiline && { minHeight: 84, textAlignVertical: 'top' }, props.style]} /></View>; }
export function Action({ title, onPress, secondary = false, disabled = false }: { title: string; onPress: () => void; secondary?: boolean; disabled?: boolean }) { return <Pressable accessibilityRole="button" accessibilityState={{ disabled }} onPress={onPress} disabled={disabled} style={({ pressed }) => [styles.button, secondary && styles.secondary, disabled && { opacity: 0.5 }, pressed && !disabled && { opacity: 0.8 }]}><Text style={[styles.buttonText, secondary && styles.secondaryText]}>{title}</Text></Pressable>; }
export function Chip({ title, active, onPress }: { title: string; active: boolean; onPress: () => void }) { return <Pressable accessibilityRole="button" accessibilityState={{ selected: active }} onPress={onPress} style={[styles.badge, { minHeight: 44, justifyContent: 'center' }, active && { backgroundColor: colors.green }]}><Text style={{ color: active ? colors.white : colors.ink, fontWeight: '600', fontSize: 13 }}>{title}</Text></Pressable>; }
export function ErrorBox({ message }: { message: string | null }) { return message ? <Text accessibilityRole="alert" style={styles.error}>{message}</Text> : null; }
export function ScreenScroll({ children }: { children: React.ReactNode }) { return <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={styles.scroll}>{children}</ScrollView>; }
export function SectionTitle({ title, aside }: { title: string; aside?: React.ReactNode }) { return <View style={[styles.row, { justifyContent: 'space-between' }]}><Text style={styles.heading}>{title}</Text>{aside}</View>; }
export function formatMonth(month: string) { const [year, mon] = month.split('-'); const names = ['janeiro','fevereiro','março','abril','maio','junho','julho','agosto','setembro','outubro','novembro','dezembro']; return `${names[Number(mon) - 1] ?? mon} de ${year}`; }
