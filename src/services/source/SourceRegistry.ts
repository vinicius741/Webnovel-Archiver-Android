import { SourceProvider } from './types';
import { RoyalRoadProvider } from './providers/RoyalRoadProvider';

class SourceRegistry {
    private providers: SourceProvider[] = [];

    constructor() {
        this.register(RoyalRoadProvider);
    }

    register(provider: SourceProvider) {
        this.providers.push(provider);
    }

    getProvider(url: string): SourceProvider | undefined {
        return this.providers.find(p => p.isSource(url));
    }
}

export const sourceRegistry = new SourceRegistry();
