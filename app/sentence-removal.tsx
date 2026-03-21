import React, { useState } from "react";
import { StyleSheet, View, ScrollView } from "react-native";
import {
  Appbar,
  ActivityIndicator,
  SegmentedButtons,
} from "react-native-paper";
import { router, Stack } from "expo-router";
import { ScreenContainer } from "../src/components/ScreenContainer";
import { TabValue } from "../src/types/sentenceRemoval";
import { useSentenceRemovalData } from "../src/hooks/useSentenceRemovalData";
import { useSentenceManagement } from "../src/hooks/useSentenceManagement";
import { useRegexRuleManagement } from "../src/hooks/useRegexRuleManagement";
import { useExportRules } from "../src/hooks/useExportRules";
import {
  SentenceList,
  RegexRuleList,
  SentenceDialog,
  RuleDialog,
} from "../src/components/sentence-removal";

export default function SentenceRemovalScreen() {
  const { sentences, regexRules, loading, saveSentences, saveRegexRules } =
    useSentenceRemovalData();
  const [activeTab, setActiveTab] = useState<TabValue>("sentences");

  const sentenceManagement = useSentenceManagement(sentences, saveSentences);
  const ruleManagement = useRegexRuleManagement(regexRules, saveRegexRules);
  const { exportRules } = useExportRules();

  const handleExport = () => {
    void exportRules(sentences, regexRules);
  };

  if (loading) {
    return (
      <ScreenContainer edges={["bottom", "left", "right"]}>
        <Stack.Screen options={{ headerShown: false }} />
        <Appbar.Header>
          <Appbar.BackAction onPress={() => router.back()} />
          <Appbar.Content title="Text Cleanup" />
        </Appbar.Header>
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" />
        </View>
      </ScreenContainer>
    );
  }

  return (
    <ScreenContainer edges={["bottom", "left", "right"]}>
      <Stack.Screen options={{ headerShown: false }} />
      <Appbar.Header>
        <Appbar.BackAction onPress={() => router.back()} />
        <Appbar.Content title="Text Cleanup" />
        <Appbar.Action
          icon="export-variant"
          onPress={handleExport}
          accessibilityLabel="Export JSON"
        />
      </Appbar.Header>

      <ScrollView contentContainerStyle={styles.content}>
        <SegmentedButtons
          value={activeTab}
          onValueChange={(value) => setActiveTab(value)}
          buttons={[
            { value: "sentences", label: "Sentences" },
            { value: "regex", label: "Regex Rules" },
          ]}
          style={styles.tabs}
        />

        {activeTab === "sentences" && (
          <SentenceList
            sentences={sentences}
            onAdd={() => sentenceManagement.openDialog()}
            onEdit={(sentence, index) => sentenceManagement.openDialog(sentence, index)}
            onDelete={sentenceManagement.handleDelete}
          />
        )}

        {activeTab === "regex" && (
          <RegexRuleList
            rules={regexRules}
            onAdd={() => ruleManagement.openDialog()}
            onEdit={ruleManagement.openDialog}
            onDelete={ruleManagement.handleDelete}
            onToggle={ruleManagement.handleToggle}
          />
        )}
      </ScrollView>

      <SentenceDialog
        visible={sentenceManagement.dialogVisible}
        sentence={sentenceManagement.sentence}
        isEditing={sentenceManagement.isEditing}
        onSentenceChange={sentenceManagement.updateSentence}
        onSave={() => sentenceManagement.handleSave(sentenceManagement.sentence)}
        onDismiss={sentenceManagement.closeDialog}
      />

      <RuleDialog
        visible={ruleManagement.dialogVisible}
        ruleDraft={ruleManagement.ruleDraft}
        previewInput={ruleManagement.previewInput}
        effectivePattern={ruleManagement.effectivePattern}
        effectiveFlags={ruleManagement.effectiveFlags}
        onDraftChange={ruleManagement.updateDraft}
        onPreviewInputChange={ruleManagement.updatePreviewInput}
        onSave={ruleManagement.handleSave}
        onDismiss={ruleManagement.closeDialog}
      />
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  loadingContainer: {
    flex: 1,
    justifyContent: "center",
    alignItems: "center",
  },
  content: {
    paddingBottom: 40,
  },
  tabs: {
    marginBottom: 8,
  },
});
