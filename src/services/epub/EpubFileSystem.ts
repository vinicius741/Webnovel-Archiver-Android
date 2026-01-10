import JSZip from 'jszip';

export class EpubFileSystem {
    private zip: JSZip;

    constructor(zip: JSZip) {
        this.zip = zip;
    }

    public static create(): EpubFileSystem {
        const zip = new JSZip();
        return new EpubFileSystem(zip);
    }

    public createMimetype(): void {
        this.zip.file('mimetype', 'application/epub+zip', { compression: 'STORE' });
    }

    public createMetaInf(): void {
        const metaInf = this.zip.folder('META-INF');
        if (!metaInf) throw new Error('Failed to create META-INF folder');
        metaInf.file('container.xml', this.generateContainerXml());
    }

    public createOEBPSFolder(): JSZip | null {
        const oebps = this.zip.folder('OEBPS');
        if (!oebps) throw new Error('Failed to create OEBPS folder');
        return oebps;
    }

    public async generate(): Promise<string> {
        return await this.zip.generateAsync({ type: 'base64' });
    }

    private generateContainerXml(): string {
        return `<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
    <rootfiles>
        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
    </rootfiles>
</container>`;
    }
}
