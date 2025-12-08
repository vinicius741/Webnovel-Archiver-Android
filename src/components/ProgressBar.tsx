import React from 'react';
import { ProgressBar as PaperProgressBar } from 'react-native-paper';

interface Props {
  progress: number;
  visible?: boolean;
}

export const AppProgressBar = ({ progress, visible = true }: Props) => {
  if (!visible) return null;
  return <PaperProgressBar progress={progress} indeterminate={progress === 0} />;
};
