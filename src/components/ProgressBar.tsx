import React from 'react';
import { View, StyleSheet } from 'react-native';
import { ProgressBar as PaperProgressBar } from 'react-native-paper';

interface Props {
  progress: number;
  visible?: boolean;
}

export const AppProgressBar = ({ progress, visible = true }: Props) => {
  if (!visible) return null;
  return (
    <View testID="progress-bar">
      <PaperProgressBar progress={progress} indeterminate={progress === 0} />
    </View>
  );
};
