import { Story, Chapter } from "../../types";

interface EpubCoverManifestItem {
  href: string;
  mediaType: string;
}

export class EpubMetadataGenerator {
  public static escapeXml(unsafe: string): string {
    return unsafe.replace(/[<>&'"]/g, (c) => {
      switch (c) {
        case "<":
          return "&lt;";
        case ">":
          return "&gt;";
        case "&":
          return "&amp;";
        case "'":
          return "&apos;";
        case '"':
          return "&quot;";
        default:
          return c;
      }
    });
  }

  public static generateBookId(story: Story): string {
    return `urn:webnovel:${story.id}`;
  }

  public static sanitizeFilename(name: string): string {
    return name.replace(/[^a-z0-9]/gi, "_").toLowerCase();
  }

  public static generateOpf(
    story: Story,
    chapters: Chapter[],
    uid: string,
    coverImage?: EpubCoverManifestItem,
  ): string {
    const manifestItems = chapters
      .map(
        (_, i) =>
          `<item id="chapter_${i + 1}" href="chapter_${i + 1}.xhtml" media-type="application/xhtml+xml"/>`,
      )
      .join("\n        ");

    const spineItems = chapters
      .map((_, i) => `<itemref idref="chapter_${i + 1}"/>`)
      .join("\n        ");
    const coverImageItem = coverImage
      ? `<item id="cover-image" href="${this.escapeXml(coverImage.href)}" media-type="${this.escapeXml(coverImage.mediaType)}"/>`
      : "";
    const coverMeta = coverImage
      ? '<meta name="cover" content="cover-image" />'
      : "";

    return `<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId" version="2.0">
    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
        <dc:title>${this.escapeXml(story.title)}</dc:title>
        <dc:creator opf:role="aut">${this.escapeXml(story.author)}</dc:creator>
        <dc:language>en</dc:language>
        <dc:identifier id="BookId">${uid}</dc:identifier>
        ${story.description ? `<dc:description>${this.escapeXml(story.description)}</dc:description>` : ""}
        ${(story.tags ?? []).map((tag) => `<dc:subject>${this.escapeXml(tag)}</dc:subject>`).join("\n        ")}
        ${coverMeta}
        <meta name="generator" content="Webnovel Archiver Android" />
    </metadata>
    <manifest>
        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
        <item id="style" href="style.css" media-type="text/css"/>
        <item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>
        <item id="details" href="details.xhtml" media-type="application/xhtml+xml"/>
        <item id="toc" href="toc.xhtml" media-type="application/xhtml+xml"/>
        ${coverImageItem}
        ${manifestItems}
    </manifest>
    <spine toc="ncx">
        <itemref idref="cover"/>
        <itemref idref="details"/>
        <itemref idref="toc"/>
        ${spineItems}
    </spine>
    <guide>
        <reference type="cover" title="Cover" href="cover.xhtml"/>
        <reference type="toc" title="Table of Contents" href="toc.xhtml"/>
    </guide>
</package>`;
  }

  public static generateNcx(
    story: Story,
    chapters: Chapter[],
    uid: string,
  ): string {
    const frontmatterNavPoints = [
      { id: "cover", label: "Cover", src: "cover.xhtml" },
      { id: "details", label: "Description and Tags", src: "details.xhtml" },
      { id: "toc", label: "Table of Contents", src: "toc.xhtml" },
    ]
      .map(
        (item, i) => `
        <navPoint id="navPoint-${item.id}" playOrder="${i + 1}">
            <navLabel>
                <text>${item.label}</text>
            </navLabel>
            <content src="${item.src}"/>
        </navPoint>`,
      )
      .join("");
    const navPoints = chapters
      .map(
        (c, i) => `
        <navPoint id="navPoint-${i + 1}" playOrder="${i + 4}">
            <navLabel>
                <text>${this.escapeXml(c.title)}</text>
            </navLabel>
            <content src="chapter_${i + 1}.xhtml"/>
        </navPoint>`,
      )
      .join("");

    return `<?xml version="1.0" encoding="UTF-8"?>
 <!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN"
   "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
    <head>
        <meta name="dtb:uid" content="${uid}"/>
        <meta name="dtb:depth" content="1"/>
        <meta name="dtb:totalPageCount" content="0"/>
        <meta name="dtb:maxPageNumber" content="0"/>
    </head>
    <docTitle>
        <text>${this.escapeXml(story.title)}</text>
    </docTitle>
    <navMap>
        ${frontmatterNavPoints}
        ${navPoints}
    </navMap>
</ncx>`;
  }
}
