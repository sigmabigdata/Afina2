/** Типы для вендорной библиотеки КриптоПро (портирована из AfinaZ). */
export interface VendorCertificate {
  thumbprint: string;
  subjectInfo: string;
  issuerInfo: string;
  serialNumber: string;
  validPeriod: { from?: Date | string; to?: Date | string } | Date;
  certApi?: unknown;
  privateKey?: unknown;
  isValid?: () => Promise<boolean>;
}

export interface VendorApi {
  getCertsList: () => Promise<VendorCertificate[]>;
  getCert: (thumbprint: string) => Promise<VendorCertificate>;
  currentCadesCert: (thumbprint: string) => Promise<unknown>;
  signBase64: (thumbprint: string, base64: string, detached?: boolean) => Promise<string>;
  signXml?: (thumbprint: string, xml: string, type?: number) => Promise<string>;
  about: () => Promise<unknown>;
}

declare const cadespluginOnload: () => Promise<VendorApi>;
export default cadespluginOnload;
