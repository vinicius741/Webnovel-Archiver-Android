import { renderHook, act } from "@testing-library/react-native";
import { router } from "expo-router";

import { useSettings } from "../useSettings";
import { storageService } from "../../../services/storage/StorageService";
import { downloadManager } from "../../../services/download/DownloadManager";
import { backupService } from "../../../services/storage/BackupService";

jest.mock("../../../services/storage/StorageService");
jest.mock("../../../services/download/DownloadManager", () => ({
  downloadManager: {
    updateSettings: jest.fn(),
  },
}));
jest.mock("../../../services/source/SourceRegistry", () => ({
  sourceRegistry: {
    getAllProviders: jest.fn().mockReturnValue([
      { name: "RoyalRoad" },
      { name: "ScribbleHub" },
    ]),
  },
}));
jest.mock("../../../services/storage/BackupService");
jest.mock("expo-router");
jest.mock("../../../context/AlertContext");

describe("useSettings", () => {
  const mockShowAlert = jest.fn();

  beforeEach(() => {
    jest.clearAllMocks();
    const { useAppAlert } = require("../../../context/AlertContext");
    useAppAlert.mockReturnValue({ showAlert: mockShowAlert });

    (storageService.getSettings as jest.Mock).mockResolvedValue({
      downloadConcurrency: 3,
      downloadDelay: 500,
      maxChaptersPerEpub: 150,
    });
    (storageService.getSourceDownloadSettings as jest.Mock).mockResolvedValue({});
    (storageService.saveSettings as jest.Mock).mockResolvedValue(undefined);
    (storageService.saveSourceDownloadSettings as jest.Mock).mockResolvedValue(undefined);
    (storageService.clearAll as jest.Mock).mockResolvedValue(undefined);

    (downloadManager.updateSettings as jest.Mock).mockImplementation(() => {});

    (backupService.exportBackup as jest.Mock).mockResolvedValue({
      success: true,
      message: "Export successful",
    });
    (backupService.importBackup as jest.Mock).mockResolvedValue({
      success: true,
      message: "Import successful",
    });
    (backupService.exportFullBackup as jest.Mock).mockResolvedValue({
      success: true,
      message: "Full backup created",
      uri: "file://backup.zip",
      filename: "backup.zip",
    });
    (backupService.shareFullBackup as jest.Mock).mockResolvedValue({
      success: true,
      message: "Full backup shared",
    });
    (backupService.importFullBackup as jest.Mock).mockResolvedValue({
      success: true,
      message: "Full import successful",
    });

    (router.back as jest.Mock).mockImplementation(() => {});
  });

  it("loads download settings on mount", async () => {
    const { result } = renderHook(() => useSettings());

    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });

    expect(storageService.getSettings).toHaveBeenCalled();
    expect(storageService.getSourceDownloadSettings).toHaveBeenCalled();
    expect(result.current.concurrency).toBe("3");
    expect(result.current.delay).toBe("500");
  });

  it("saves validated concurrency while preserving stored epub max setting", async () => {
    const { result } = renderHook(() => useSettings());

    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });

    act(() => {
      result.current.handleConcurrencyChange("5");
    });

    await act(async () => {
      result.current.handleConcurrencyBlur();
      await new Promise((resolve) => setTimeout(resolve, 0));
    });

    expect(storageService.saveSettings).toHaveBeenCalledWith({
      downloadConcurrency: 5,
      downloadDelay: 500,
      maxChaptersPerEpub: 150,
    });
    expect(downloadManager.updateSettings).toHaveBeenCalledWith(
      5,
      500,
      {},
    );
  });

  it("validates delay and clamps negatives to 0 on blur", async () => {
    const { result } = renderHook(() => useSettings());

    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });

    act(() => {
      result.current.handleDelayChange("-100");
    });
    expect(result.current.delayError).toBe("Must be 0 or greater");

    await act(async () => {
      result.current.handleDelayBlur();
      await new Promise((resolve) => setTimeout(resolve, 0));
    });

    expect(result.current.delay).toBe("0");
    expect(storageService.saveSettings).toHaveBeenCalledWith({
      downloadConcurrency: 3,
      downloadDelay: 0,
      maxChaptersPerEpub: 150,
    });
  });

  it("saves the current delay when explicitly saved without blur", async () => {
    const { result } = renderHook(() => useSettings());

    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });

    act(() => {
      result.current.handleDelayChange("1200");
    });

    await act(async () => {
      await result.current.saveSettings();
    });

    expect(storageService.saveSettings).toHaveBeenCalledWith({
      downloadConcurrency: 3,
      downloadDelay: 1200,
      maxChaptersPerEpub: 150,
    });
    expect(downloadManager.updateSettings).toHaveBeenCalledWith(3, 1200, {});
  });

  it("clears data after delete confirmation", async () => {
    const { result } = renderHook(() => useSettings());

    act(() => {
      result.current.clearData();
    });

    const clearAlertButtons = mockShowAlert.mock.calls[0][2];
    const deleteButton = clearAlertButtons.find(
      (button: any) => button.text === "Delete",
    );

    await act(async () => {
      await deleteButton.onPress();
    });

    expect(storageService.clearAll).toHaveBeenCalled();
    expect(mockShowAlert).toHaveBeenCalledWith(
      "Data Cleared",
      "All data has been deleted.",
      expect.any(Array),
    );
  });

  it("exports backup and shows success alert", async () => {
    const { result } = renderHook(() => useSettings());

    await act(async () => {
      await result.current.handleExportBackup();
    });

    expect(backupService.exportBackup).toHaveBeenCalled();
    expect(mockShowAlert).toHaveBeenCalledWith(
      "Export Complete",
      "Export successful",
    );
  });

  it("sets loading state while exporting a full backup", async () => {
    let resolveExport: (
      value: { success: boolean; message: string; uri?: string },
    ) => void = () => {};
    (backupService.exportFullBackup as jest.Mock).mockReturnValue(
      new Promise((resolve) => {
        resolveExport = resolve;
      }),
    );
    const { result } = renderHook(() => useSettings());

    act(() => {
      void result.current.handleExportFullBackup();
    });

    expect(result.current.isExportingFullBackup).toBe(true);

    await act(async () => {
      resolveExport({
        success: true,
        message: "Full backup created",
        uri: "file://backup.zip",
      });
    });

    expect(result.current.isExportingFullBackup).toBe(false);
    expect(mockShowAlert).toHaveBeenCalledWith(
      "Backup Created",
      "Full backup created",
      expect.any(Array),
    );
  });

  it("ignores repeated full backup export presses while export is running", async () => {
    let resolveExport: (
      value: { success: boolean; message: string; uri?: string },
    ) => void = () => {};
    (backupService.exportFullBackup as jest.Mock).mockReturnValue(
      new Promise((resolve) => {
        resolveExport = resolve;
      }),
    );
    const { result } = renderHook(() => useSettings());

    act(() => {
      void result.current.handleExportFullBackup();
      void result.current.handleExportFullBackup();
    });

    expect(backupService.exportFullBackup).toHaveBeenCalledTimes(1);

    await act(async () => {
      resolveExport({ success: true, message: "Full backup created" });
    });
  });

  it("shares a created full backup from the completion dialog", async () => {
    const { result } = renderHook(() => useSettings());

    await act(async () => {
      await result.current.handleExportFullBackup();
    });

    const exportAlertButtons = mockShowAlert.mock.calls[0][2];
    const shareButton = exportAlertButtons.find(
      (button: any) => button.text === "Share Backup",
    );

    await act(async () => {
      await shareButton.onPress();
    });

    expect(backupService.shareFullBackup).toHaveBeenCalledWith(
      "file://backup.zip",
    );
    expect(mockShowAlert).toHaveBeenCalledWith(
      "Share Complete",
      "Full backup shared",
    );
  });

  it("imports backup after confirmation", async () => {
    const { result } = renderHook(() => useSettings());

    act(() => {
      result.current.handleImportBackup();
    });

    const importAlertButtons = mockShowAlert.mock.calls[0][2];
    const importButton = importAlertButtons.find(
      (button: any) => button.text === "Import",
    );

    await act(async () => {
      await importButton.onPress();
    });

    expect(backupService.importBackup).toHaveBeenCalled();
    expect(mockShowAlert).toHaveBeenCalledWith(
      "Import Complete",
      "Import successful",
      expect.any(Array),
    );
  });

  describe("Source-Specific Settings", () => {
    it("exposes available providers", async () => {
      const { result } = renderHook(() => useSettings());

      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 0));
      });

      expect(result.current.availableProviders).toEqual([
        "RoyalRoad",
        "ScribbleHub",
      ]);
    });

    it("shows global values when no source is selected", async () => {
      const { result } = renderHook(() => useSettings());

      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 0));
      });

      expect(result.current.selectedSource).toBeNull();
      expect(result.current.concurrency).toBe("3");
      expect(result.current.delay).toBe("500");
    });

    it("shows source override values when a source is selected", async () => {
      (storageService.getSourceDownloadSettings as jest.Mock).mockResolvedValue({
        ScribbleHub: { concurrency: 1, delay: 2000 },
      });

      const { result } = renderHook(() => useSettings());

      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 0));
      });

      act(() => {
        result.current.setSelectedSource("ScribbleHub");
      });

      expect(result.current.concurrency).toBe("1");
      expect(result.current.delay).toBe("2000");
    });

    it("falls back to global values when selected source has no override", async () => {
      const { result } = renderHook(() => useSettings());

      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 0));
      });

      act(() => {
        result.current.setSelectedSource("RoyalRoad");
      });

      expect(result.current.concurrency).toBe("3");
      expect(result.current.delay).toBe("500");
    });

    it("saves source override on blur", async () => {
      const { result } = renderHook(() => useSettings());

      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 0));
      });

      act(() => {
        result.current.setSelectedSource("ScribbleHub");
      });

      act(() => {
        result.current.handleConcurrencyChange("1");
      });

      act(() => {
        result.current.handleDelayChange("2000");
      });

      await act(async () => {
        result.current.handleConcurrencyBlur();
        await new Promise((resolve) => setTimeout(resolve, 0));
      });

      expect(storageService.saveSourceDownloadSettings).toHaveBeenCalledWith({
        ScribbleHub: { concurrency: 1, delay: 2000 },
      });
      expect(downloadManager.updateSettings).toHaveBeenCalledWith(
        3,
        500,
        { ScribbleHub: { concurrency: 1, delay: 2000 } },
      );
    });

    it("resets source override", async () => {
      (storageService.getSourceDownloadSettings as jest.Mock).mockResolvedValue({
        ScribbleHub: { concurrency: 1, delay: 2000 },
      });

      const { result } = renderHook(() => useSettings());

      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 0));
      });

      act(() => {
        result.current.setSelectedSource("ScribbleHub");
      });

      await act(async () => {
        result.current.handleResetSource();
      });

      expect(storageService.saveSourceDownloadSettings).toHaveBeenCalledWith({});
    });
  });
});
