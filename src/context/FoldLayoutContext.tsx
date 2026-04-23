import React, {
  createContext,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";

import { storageService } from "../services/StorageService";
import type { FoldLayoutMode } from "../types";

interface FoldLayoutContextValue {
  foldLayoutMode: FoldLayoutMode;
  setFoldLayoutMode: (mode: FoldLayoutMode) => Promise<void>;
}

const FoldLayoutContext = createContext<FoldLayoutContextValue>({
  foldLayoutMode: "auto",
  setFoldLayoutMode: async () => {},
});

export const useFoldLayoutMode = () => useContext(FoldLayoutContext);

export const FoldLayoutProvider = ({ children }: { children: ReactNode }) => {
  const [foldLayoutMode, setFoldLayoutModeState] = useState<FoldLayoutMode>("auto");
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let mounted = true;

    void (async () => {
      const mode = await storageService.getFoldLayoutMode();
      if (!mounted) {
        return;
      }
      setFoldLayoutModeState(mode);
      setReady(true);
    })();

    return () => {
      mounted = false;
    };
  }, []);

  const setFoldLayoutMode = async (mode: FoldLayoutMode) => {
    setFoldLayoutModeState(mode);
    await storageService.saveFoldLayoutMode(mode);
  };

  if (!ready) {
    return null;
  }

  return (
    <FoldLayoutContext.Provider value={{ foldLayoutMode, setFoldLayoutMode }}>
      {children}
    </FoldLayoutContext.Provider>
  );
};
