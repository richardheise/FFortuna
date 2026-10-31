import React, { forwardRef } from 'react';
import { StyleSheet, Text as NativeText, TextInput as NativeTextInput, type TextProps, type TextInputProps } from 'react-native';
const family = (style: TextProps['style']) => {
  const weight = StyleSheet.flatten(style)?.fontWeight;
  return weight === 'bold' || Number(weight) >= 700 ? 'SpaceGrotesk-Bold' : Number(weight) >= 500 ? 'SpaceGrotesk-Medium' : 'SpaceGrotesk-Regular';
};
export function Text({ style, ...props }: TextProps) {
  return <NativeText {...props} style={[style, { fontFamily: family(style), fontWeight: 'normal' }]} />;
}
export const TextInput = forwardRef<NativeTextInput, TextInputProps>(function TextInput({ style, ...props }, ref) {
  return <NativeTextInput ref={ref} {...props} style={[style, { fontFamily: family(style), fontWeight: 'normal' }]} />;
});
