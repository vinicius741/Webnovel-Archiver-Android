import React, { useState, useEffect } from 'react';
import { StyleSheet, View } from 'react-native';
import { TextInput, Text, Switch } from 'react-native-paper';
import { QuickBuilderConfig } from '../../types/sentenceRemoval';

interface QuickBuilderFormProps {
  config: QuickBuilderConfig;
  onChange: (config: QuickBuilderConfig) => void;
}

export function QuickBuilderForm({
  config,
  onChange,
}: QuickBuilderFormProps) {
  const { characters, minCount, wholeLine } = config;
  const [minCountInput, setMinCountInput] = useState(minCount.toString());

  // Sync input with external config changes
  useEffect(() => {
    setMinCountInput(minCount.toString());
  }, [minCount]);

  const handleMinCountChange = (value: string) => {
    setMinCountInput(value);

    if (value === '') {
      return;
    }

    const num = parseInt(value, 10);
    if (!isNaN(num) && num >= 1) {
      onChange({ ...config, minCount: num });
    }
  };

  const handleMinCountBlur = () => {
    const num = parseInt(minCountInput, 10);
    if (isNaN(num) || num < 1) {
      const fallback = 1;
      setMinCountInput(fallback.toString());
      onChange({ ...config, minCount: fallback });
    }
  };

  return (
    <View style={styles.container}>
      <TextInput
        label="Character(s)"
        value={characters}
        onChangeText={(value) => onChange({ ...config, characters: value })}
        mode="outlined"
        style={styles.input}
        placeholder="e.g. =, -, ##, **"
        autoCapitalize="none"
        autoCorrect={false}
      />
      <Text variant="bodySmall" style={styles.helpText}>
        Enter the character(s) that form the separator. Use 1-2 characters for best results.
      </Text>

      <TextInput
        label="Minimum repetitions"
        value={minCountInput}
        onChangeText={handleMinCountChange}
        onBlur={handleMinCountBlur}
        mode="outlined"
        style={styles.input}
        keyboardType="numeric"
      />
      <Text variant="bodySmall" style={styles.helpText}>
        Match when the character repeats at least this many times (e.g., 5 means ===== or more).
      </Text>

      <View style={styles.switchRow}>
        <View style={styles.switchText}>
          <Text variant="bodyMedium">Whole line only</Text>
          <Text variant="bodySmall" style={styles.switchHint}>
            Match entire lines, not inline patterns
          </Text>
        </View>
        <Switch
          value={wholeLine}
          onValueChange={(value) => onChange({ ...config, wholeLine: value })}
        />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    gap: 2,
  },
  input: {
    marginBottom: 2,
  },
  helpText: {
    opacity: 0.7,
    marginBottom: 6,
    paddingHorizontal: 4,
  },
  switchRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 4,
    marginTop: 4,
  },
  switchText: {
    flex: 1,
    marginRight: 12,
  },
  switchHint: {
    opacity: 0.6,
    marginTop: 2,
  },
});
