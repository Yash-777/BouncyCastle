package org.bouncycastle.pkix.jcajce;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.cert.CRLException;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertStore;
import java.security.cert.CertStoreException;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.security.cert.X509CRLEntry;
import java.security.cert.X509CRLSelector;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.security.interfaces.DSAParams;
import java.security.interfaces.DSAPublicKey;
import java.security.spec.DSAPublicKeySpec;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.security.auth.x500.X500Principal;

import org.bouncycastle.asn1.ASN1Enumerated;
import org.bouncycastle.asn1.ASN1GeneralizedTime;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.isismtt.ISISMTTObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.RFC4519Style;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.IssuingDistributionPoint;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.jcajce.PKIXCRLStore;
import org.bouncycastle.jcajce.PKIXCRLStoreSelector;
import org.bouncycastle.jcajce.PKIXCertStore;
import org.bouncycastle.jcajce.PKIXCertStoreSelector;
import org.bouncycastle.jcajce.PKIXExtendedParameters;
import org.bouncycastle.jcajce.util.JcaJceHelper;
import org.bouncycastle.util.Selector;
import org.bouncycastle.util.Store;
import org.bouncycastle.util.StoreException;

class RevocationUtilities
{
    protected static final PKIXCRLUtil CRL_UTIL = new PKIXCRLUtil();

    protected static final String CERTIFICATE_POLICIES = Extension.certificatePolicies.getId();
    protected static final String BASIC_CONSTRAINTS = Extension.basicConstraints.getId();
    protected static final String POLICY_MAPPINGS = Extension.policyMappings.getId();
    protected static final String SUBJECT_ALTERNATIVE_NAME = Extension.subjectAlternativeName.getId();
    protected static final String NAME_CONSTRAINTS = Extension.nameConstraints.getId();
    protected static final String KEY_USAGE = Extension.keyUsage.getId();
    protected static final String INHIBIT_ANY_POLICY = Extension.inhibitAnyPolicy.getId();
    protected static final String ISSUING_DISTRIBUTION_POINT = Extension.issuingDistributionPoint.getId();
    protected static final String DELTA_CRL_INDICATOR = Extension.deltaCRLIndicator.getId();
    protected static final String POLICY_CONSTRAINTS = Extension.policyConstraints.getId();
    protected static final String FRESHEST_CRL = Extension.freshestCRL.getId();
    protected static final String CRL_DISTRIBUTION_POINTS = Extension.cRLDistributionPoints.getId();
    protected static final String AUTHORITY_KEY_IDENTIFIER = Extension.authorityKeyIdentifier.getId();

    protected static final String ANY_POLICY = "2.5.29.32.0";

    protected static final String CRL_NUMBER = Extension.cRLNumber.getId();

    /*
    * key usage bits
    */
    protected static final int KEY_CERT_SIGN = 5;
    protected static final int CRL_SIGN = 6;

    protected static final String[] crlReasons = new String[]{
        "unspecified",
        "keyCompromise",
        "cACompromise",
        "affiliationChanged",
        "superseded",
        "cessationOfOperation",
        "certificateHold",
        "unknown",
        "removeFromCRL",
        "privilegeWithdrawn",
        "aACompromise"};

    /**
     * Search the given Set of TrustAnchor's for one that is the
     * issuer of the given X509 certificate. Uses the default provider
     * for signature verification.
     *
     * @param cert         the X509 certificate
     * @param trustAnchors a Set of TrustAnchor's
     * @return the <code>TrustAnchor</code> object if found or
     *         <code>null</code> if not.
     * @throws AnnotatedException if a TrustAnchor was found but the signature verification
     * on the given certificate has thrown an exception.
     */
    protected static TrustAnchor findTrustAnchor(
        X509Certificate cert,
        Set trustAnchors)
        throws AnnotatedException
    {
        return findTrustAnchor(cert, trustAnchors, null);
    }

    /**
     * Search the given Set of TrustAnchor's for one that is the
     * issuer of the given X509 certificate. Uses the specified
     * provider for signature verification, or the default provider
     * if null.
     *
     * @param cert         the X509 certificate
     * @param trustAnchors a Set of TrustAnchor's
     * @param sigProvider  the provider to use for signature verification
     * @return the <code>TrustAnchor</code> object if found or
     *         <code>null</code> if not.
     * @throws AnnotatedException if a TrustAnchor was found but the signature verification
     * on the given certificate has thrown an exception.
     */
    protected static TrustAnchor findTrustAnchor(
        X509Certificate cert,
        Set trustAnchors,
        String sigProvider)
        throws AnnotatedException
    {
        TrustAnchor trust = null;
        PublicKey trustPublicKey = null;
        Exception invalidKeyEx = null;

        X509CertSelector certSelectX509 = new X509CertSelector();
        X500Name certIssuer = getIssuer(cert);

        try
        {
            certSelectX509.setSubject(certIssuer.getEncoded());
        }
        catch (IOException ex)
        {
            throw new AnnotatedException("Cannot set subject search criteria for trust anchor.", ex);
        }

        Iterator iter = trustAnchors.iterator();
        while (iter.hasNext() && trust == null)
        {
            trust = (TrustAnchor)iter.next();
            if (trust.getTrustedCert() != null)
            {
                if (certSelectX509.match(trust.getTrustedCert()))
                {
                    trustPublicKey = trust.getTrustedCert().getPublicKey();
                }
                else
                {
                    trust = null;
                }
            }
            else if (trust.getCAName() != null
                && trust.getCAPublicKey() != null)
            {
                try
                {
                    X500Name caName = getX500Name(trust.getCA());
                    if (certIssuer.equals(caName))
                    {
                        trustPublicKey = trust.getCAPublicKey();
                    }
                    else
                    {
                        trust = null;
                    }
                }
                catch (IllegalArgumentException ex)
                {
                    trust = null;
                }
            }
            else
            {
                trust = null;
            }

            if (trustPublicKey != null)
            {
                try
                {
                    verifyX509Certificate(cert, trustPublicKey, sigProvider);
                }
                catch (Exception ex)
                {
                    invalidKeyEx = ex;
                    trust = null;
                    trustPublicKey = null;
                }
            }
        }

        if (trust == null && invalidKeyEx != null)
        {
            throw new AnnotatedException("TrustAnchor found but certificate validation failed.", invalidKeyEx);
        }

        return trust;
    }

    static boolean isIssuerTrustAnchor(
        X509Certificate cert,
        Set trustAnchors,
        String sigProvider)
        throws AnnotatedException
    {
        try
        {
            return findTrustAnchor(cert, trustAnchors, sigProvider) != null;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    static List<PKIXCertStore> getAdditionalStoresFromAltNames(
        byte[] issuerAlternativeName,
        Map<GeneralName, PKIXCertStore> altNameCertStoreMap)
        throws CertificateParsingException
    {
        // if in the IssuerAltName extension an URI
        // is given, add an additional X.509 store
        if (issuerAlternativeName != null)
        {
            GeneralNames issuerAltName = GeneralNames.getInstance(ASN1OctetString.getInstance(issuerAlternativeName).getOctets());

            GeneralName[] names = issuerAltName.getNames();
            List<PKIXCertStore>  stores = new ArrayList<PKIXCertStore>();

            for (int i = 0; i != names.length; i++)
            {
                GeneralName altName = names[i];

                PKIXCertStore altStore = altNameCertStoreMap.get(altName);

                if (altStore != null)
                {
                    stores.add(altStore);
                }
            }

            return stores;
        }
        else
        {
            return Collections.EMPTY_LIST;
        }
    }

    protected static Date getValidDate(PKIXExtendedParameters paramsPKIX)
    {
        Date validDate = paramsPKIX.getDate();

        if (validDate == null)
        {
            validDate = new Date();
        }

        return validDate;
    }

    protected static boolean isSelfIssued(X509Certificate cert)
    {
        return cert.getSubjectDN().equals(cert.getIssuerDN());
    }


    /**
     * Extract the value of the given extension, if it exists.
     *
     * @param ext The extension object.
     * @param oid The object identifier to obtain.
     * @throws AnnotatedException if the extension cannot be read.
     */
    protected static ASN1Primitive getExtensionValue(
        java.security.cert.X509Extension ext,
        ASN1ObjectIdentifier oid)
        throws AnnotatedException
    {
        byte[] bytes = ext.getExtensionValue(oid.getId());
        if (bytes == null)
        {
            return null;
        }

        return getObject(oid, bytes);
    }

    private static ASN1Primitive getObject(
        ASN1ObjectIdentifier oid,
        byte[] ext)
        throws AnnotatedException
    {
        try
        {
            return ASN1Primitive.fromByteArray(ASN1OctetString.getInstance(ext).getOctets());
        }
        catch (Exception e)
        {
            throw new AnnotatedException("exception processing extension " + oid, e);
        }
    }

    protected static AlgorithmIdentifier getAlgorithmIdentifier(
        PublicKey key)
        throws CertPathValidatorException
    {
        try
        {
            ASN1InputStream aIn = new ASN1InputStream(key.getEncoded());

            SubjectPublicKeyInfo info = SubjectPublicKeyInfo.getInstance(aIn.readObject());

            return info.getAlgorithm();
        }
        catch (Exception e)
        {
            throw new CertPathValidatorException("subject public key cannot be decoded", e);
        }
    }

    // crl checking

    /**
     * Return a Collection of all certificates or attribute certificates found
     * in the X509Store's that are matching the certSelect criteriums.
     *
     * @param certSelect a {@link Selector} object that will be used to select
     *                   the certificates
     * @param certStores a List containing only {@link Store} objects. These
     *                   are used to search for certificates.
     * @return a Collection of all found {@link X509Certificate}
     *         May be empty but never <code>null</code>.
     */
    protected static Collection findCertificates(PKIXCertStoreSelector certSelect,
                                                 List certStores)
        throws AnnotatedException
    {
        Set certs = new LinkedHashSet();
        Iterator iter = certStores.iterator();

        while (iter.hasNext())
        {
            Object obj = iter.next();

            if (obj instanceof Store)
            {
                Store certStore = (Store)obj;
                try
                {
                    certs.addAll(certStore.getMatches(certSelect));
                }
                catch (StoreException e)
                {
                    throw new AnnotatedException(
                            "Problem while picking certificates from X.509 store.", e);
                }
            }
            else
            {
                CertStore certStore = (CertStore)obj;

                try
                {
                    certs.addAll(PKIXCertStoreSelector.getCertificates(certSelect, certStore));
                }
                catch (CertStoreException e)
                {
                    throw new AnnotatedException(
                        "Problem while picking certificates from certificate store.",
                        e);
                }
            }
        }
        return certs;
    }

    static List<PKIXCRLStore> getAdditionalStoresFromCRLDistributionPoint(CRLDistPoint crldp, Map<GeneralName, PKIXCRLStore> namedCRLStoreMap)
        throws AnnotatedException
    {
        if (crldp != null)
        {
            DistributionPoint dps[] = null;
            try
            {
                dps = crldp.getDistributionPoints();
            }
            catch (Exception e)
            {
                throw new AnnotatedException(
                    "Distribution points could not be read.", e);
            }
            List<PKIXCRLStore> stores = new ArrayList<PKIXCRLStore>();

            for (int i = 0; i < dps.length; i++)
            {
                DistributionPointName dpn = dps[i].getDistributionPoint();
                // look for URIs in fullName
                if (dpn != null)
                {
                    if (dpn.getType() == DistributionPointName.FULL_NAME)
                    {
                        GeneralName[] genNames = GeneralNames.getInstance(
                            dpn.getName()).getNames();

                        for (int j = 0; j < genNames.length; j++)
                        {
                            PKIXCRLStore store = namedCRLStoreMap.get(genNames[j]);
                            if (store != null)
                            {
                                stores.add(store);
                            }
                        }
                    }
                }
            }

            return stores;
        }
        else
        {
            return Collections.EMPTY_LIST;
        }
    }

    /**
     * Add the CRL issuers from the cRLIssuer field of the distribution point or
     * from the certificate if not given to the issuer criterion of the
     * <code>selector</code>.
     * <p>
     * The <code>issuerPrincipals</code> are a collection with a single
     * <code>X500Name</code> for <code>X509Certificate</code>s.
     * </p>
     * @param dp               The distribution point.
     * @param issuerPrincipals The issuers of the certificate or attribute
     *                         certificate which contains the distribution point.
     * @param selector         The CRL selector.
     * @throws AnnotatedException if an exception occurs while processing.
     * @throws ClassCastException if <code>issuerPrincipals</code> does not
     * contain only <code>X500Name</code>s.
     */
    protected static void getCRLIssuersFromDistributionPoint(
        DistributionPoint dp,
        Collection issuerPrincipals,
        X509CRLSelector selector)
        throws AnnotatedException
    {
        List issuers = new ArrayList();
        // indirect CRL
        if (dp.getCRLIssuer() != null)
        {
            GeneralName genNames[] = dp.getCRLIssuer().getNames();
            // look for a DN
            for (int j = 0; j < genNames.length; j++)
            {
                if (genNames[j].getTagNo() == GeneralName.directoryName)
                {
                    try
                    {
                        issuers.add(X500Name.getInstance(genNames[j].getName()
                            .toASN1Primitive().getEncoded()));
                    }
                    catch (IOException e)
                    {
                        throw new AnnotatedException(
                            "CRL issuer information from distribution point cannot be decoded.",
                            e);
                    }
                }
            }
        }
        else
        {
            /*
             * certificate issuer is CRL issuer, distributionPoint field MUST be
             * present.
             */
            if (dp.getDistributionPoint() == null)
            {
                throw new AnnotatedException(
                    "CRL issuer is omitted from distribution point but no distributionPoint field present.");
            }
            // add and check issuer principals
            for (Iterator it = issuerPrincipals.iterator(); it.hasNext(); )
            {
                issuers.add(it.next());
            }
        }
        // TODO: is not found although this should correctly add the rel name. selector of Sun is buggy here or PKI test case is invalid
        // distributionPoint
//        if (dp.getDistributionPoint() != null)
//        {
//            // look for nameRelativeToCRLIssuer
//            if (dp.getDistributionPoint().getType() == DistributionPointName.NAME_RELATIVE_TO_CRL_ISSUER)
//            {
//                // append fragment to issuer, only one
//                // issuer can be there, if this is given
//                if (issuers.size() != 1)
//                {
//                    throw new AnnotatedException(
//                        "nameRelativeToCRLIssuer field is given but more than one CRL issuer is given.");
//                }
//                ASN1Encodable relName = dp.getDistributionPoint().getName();
//                Iterator it = issuers.iterator();
//                List issuersTemp = new ArrayList(issuers.size());
//                while (it.hasNext())
//                {
//                    Enumeration e = null;
//                    try
//                    {
//                        e = ASN1Sequence.getInstance(
//                            new ASN1InputStream(((X500Principal) it.next())
//                                .getEncoded()).readObject()).getObjects();
//                    }
//                    catch (IOException ex)
//                    {
//                        throw new AnnotatedException(
//                            "Cannot decode CRL issuer information.", ex);
//                    }
//                    ASN1EncodableVector v = new ASN1EncodableVector();
//                    while (e.hasMoreElements())
//                    {
//                        v.add((ASN1Encodable) e.nextElement());
//                    }
//                    v.add(relName);
//                    issuersTemp.add(new X500Principal(new DERSequence(v)
//                        .getDEREncoded()));
//                }
//                issuers.clear();
//                issuers.addAll(issuersTemp);
//            }
//        }
        Iterator it = issuers.iterator();
        while (it.hasNext())
        {
            try
            {
                selector.addIssuerName(((X500Name)it.next()).getEncoded());
            }
            catch (IOException ex)
            {
                throw new AnnotatedException(
                    "Cannot decode CRL issuer information.", ex);
            }
        }
    }

    protected static void getCertStatus(
        Date validDate,
        X509CRL crl,
        Object cert,
        CertStatus certStatus)
        throws AnnotatedException
    {
        boolean isIndirect;
        try
        {
            isIndirect = isIndirectCRL(crl);
        }
        catch (CRLException exception)
        {
            throw new AnnotatedException("Failed check for indirect CRL.", exception);
        }

        X509Certificate x509Cert = (X509Certificate)cert;
        X500Name x509CertIssuer = getIssuer(x509Cert);

        if (!isIndirect)
        {
            X500Name crlIssuer = getIssuer(crl);
            if (!x509CertIssuer.equals(crlIssuer))
            {
                return;
            }
        }

        X509CRLEntry crl_entry = crl.getRevokedCertificate(x509Cert.getSerialNumber());
        if (null == crl_entry)
        {
            return;
        }

        if (isIndirect)
        {
            X500Principal certificateIssuer = crl_entry.getCertificateIssuer();

            X500Name expectedCertIssuer;
            if (null == certificateIssuer)
            {
                expectedCertIssuer = getIssuer(crl);
            }
            else
            {
                expectedCertIssuer = getX500Name(certificateIssuer);
            }

            if (!x509CertIssuer.equals(expectedCertIssuer))
            {
                return;
            }
        }

        int reasonCodeValue = CRLReason.unspecified;

        if (crl_entry.hasExtensions())
        {
            try
            {
                ASN1Primitive extValue = RevocationUtilities.getExtensionValue(crl_entry, Extension.reasonCode);
                ASN1Enumerated reasonCode = ASN1Enumerated.getInstance(extValue);
                if (null != reasonCode)
                {
                    reasonCodeValue = reasonCode.intValueExact();
                }
            }
            catch (Exception e)
            {
                throw new AnnotatedException("Reason code CRL entry extension could not be decoded.", e);
            }
        }

        Date revocationDate = crl_entry.getRevocationDate();

        if (validDate.before(revocationDate))
        {
            switch (reasonCodeValue)
            {
            case CRLReason.unspecified:
            case CRLReason.keyCompromise:
            case CRLReason.cACompromise:
            case CRLReason.aACompromise:
                break;
            default:
                return;
            }
        }

        // (i) or (j)
        certStatus.setCertStatus(reasonCodeValue);
        certStatus.setRevocationDate(revocationDate);
    }

    /**
     * Fetches delta CRLs according to RFC 3280 section 5.2.4.
     *
     * @param validityDate The date for which the delta CRLs must be valid.
     * @param completeCRL The complete CRL the delta CRL is for.
     * @return A <code>Set</code> of <code>X509CRL</code>s with delta CRLs.
     * @throws AnnotatedException if an exception occurs while picking the delta
     * CRLs.
     */
    protected static Set getDeltaCRLs(Date validityDate,
                                      X509CRL completeCRL, List<CertStore> certStores, List<PKIXCRLStore> pkixCrlStores)
        throws AnnotatedException
    {
        X509CRLSelector baseDeltaSelect = new X509CRLSelector();
        // 5.2.4 (a)
        try
        {
            baseDeltaSelect.addIssuerName(completeCRL.getIssuerX500Principal().getEncoded());
        }
        catch (IOException e)
        {
            throw new AnnotatedException("cannot extract issuer from CRL.", e);
        }

        BigInteger completeCRLNumber = null;
        try
        {
            ASN1Primitive derObject = RevocationUtilities.getExtensionValue(completeCRL,
                Extension.cRLNumber);
            if (derObject != null)
            {
                completeCRLNumber = ASN1Integer.getInstance(derObject).getPositiveValue();
            }
        }
        catch (Exception e)
        {
            throw new AnnotatedException(
                "cannot extract CRL number extension from CRL", e);
        }

        // 5.2.4 (b)
        byte[] idp = null;
        try
        {
            idp = completeCRL.getExtensionValue(ISSUING_DISTRIBUTION_POINT);
        }
        catch (Exception e)
        {
            throw new AnnotatedException(
                "issuing distribution point extension value could not be read",
                e);
        }

        // 5.2.4 (d)

        baseDeltaSelect.setMinCRLNumber(completeCRLNumber == null ? null : completeCRLNumber
            .add(BigInteger.valueOf(1)));

        PKIXCRLStoreSelector.Builder selBuilder = new PKIXCRLStoreSelector.Builder(baseDeltaSelect);

        selBuilder.setIssuingDistributionPoint(idp);
        selBuilder.setIssuingDistributionPointEnabled(true);

        // 5.2.4 (c)
        selBuilder.setMaxBaseCRLNumber(completeCRLNumber);

        PKIXCRLStoreSelector deltaSelect = selBuilder.build();

        // find delta CRLs
        Set temp = CRL_UTIL.findCRLs(deltaSelect, validityDate, certStores, pkixCrlStores);

        Set result = new HashSet();

        for (Iterator it = temp.iterator(); it.hasNext(); )
        {
            X509CRL crl = (X509CRL)it.next();

            if (isDeltaCRL(crl))
            {
                result.add(crl);
            }
        }

        return result;
    }

    private static boolean isDeltaCRL(X509CRL crl)
    {
        Set critical = crl.getCriticalExtensionOIDs();

        if (critical == null)
        {
            return false;
        }

        return critical.contains(RFC3280CertPathUtilities.DELTA_CRL_INDICATOR);
    }

    /**
     * Fetches complete CRLs according to RFC 3280.
     *
     * @param dp          The distribution point for which the complete CRL
     * @param cert        The <code>X509Certificate</code> for
     *                    which the CRL should be searched.
     * @return A <code>Set</code> of <code>X509CRL</code>s with complete
     *         CRLs.
     * @throws AnnotatedException if an exception occurs while picking the CRLs
     * or no CRLs are found.
     */
    protected static Set getCompleteCRLs(DistributionPoint dp, Object cert, Date validityDate, List certStores, List crlStores)
        throws AnnotatedException, CRLNotFoundException
    {
        X509CRLSelector baseCrlSelect = new X509CRLSelector();

        try
        {
            Set issuers = new HashSet();
            issuers.add(getIssuer((X509Certificate)cert));

            RevocationUtilities.getCRLIssuersFromDistributionPoint(dp, issuers, baseCrlSelect);
        }
        catch (AnnotatedException e)
        {
            throw new AnnotatedException(
                "Could not get issuer information from distribution point.", e);
        }

        if (cert instanceof X509Certificate)
        {
            baseCrlSelect.setCertificateChecking((X509Certificate)cert);
        }

        PKIXCRLStoreSelector crlSelect = new PKIXCRLStoreSelector.Builder(baseCrlSelect).setCompleteCRLEnabled(true).build();

        Set crls = CRL_UTIL.findCRLs(crlSelect, validityDate, certStores, crlStores);

        checkCRLsNotEmpty(crls, cert);

        return crls;
    }

    protected static Date getValidCertDateFromValidityModel(
        PKIXExtendedParameters paramsPKIX, CertPath certPath, int index)
        throws AnnotatedException
    {
        if (paramsPKIX.getValidityModel() == PKIXExtendedParameters.CHAIN_VALIDITY_MODEL)
        {
            // if end cert use given signing/encryption/... time
            if (index <= 0)
            {
                return RevocationUtilities.getValidDate(paramsPKIX);
                // else use time when previous cert was created
            }
            else
            {
                if (index - 1 == 0)
                {
                    ASN1GeneralizedTime dateOfCertgen = null;
                    try
                    {
                        byte[] extBytes = ((X509Certificate)certPath.getCertificates().get(index - 1)).getExtensionValue(ISISMTTObjectIdentifiers.id_isismtt_at_dateOfCertGen.getId());
                        if (extBytes != null)
                        {
                            dateOfCertgen = ASN1GeneralizedTime.getInstance(ASN1Primitive.fromByteArray(extBytes));
                        }
                    }
                    catch (IOException e)
                    {
                        throw new AnnotatedException(
                            "Date of cert gen extension could not be read.");
                    }
                    catch (IllegalArgumentException e)
                    {
                        throw new AnnotatedException(
                            "Date of cert gen extension could not be read.");
                    }
                    if (dateOfCertgen != null)
                    {
                        try
                        {
                            return dateOfCertgen.getDate();
                        }
                        catch (ParseException e)
                        {
                            throw new AnnotatedException(
                                "Date from date of cert gen extension could not be parsed.",
                                e);
                        }
                    }
                    return ((X509Certificate)certPath.getCertificates().get(
                        index - 1)).getNotBefore();
                }
                else
                {
                    return ((X509Certificate)certPath.getCertificates().get(
                        index - 1)).getNotBefore();
                }
            }
        }
        else
        {
            return getValidDate(paramsPKIX);
        }
    }

    /**
     * Return the next working key inheriting DSA parameters if necessary.
     * <p>
     * This methods inherits DSA parameters from the indexed certificate or
     * previous certificates in the certificate chain to the returned
     * <code>PublicKey</code>. The list is searched upwards, meaning the end
     * certificate is at position 0 and previous certificates are following.
     * </p>
     * <p>
     * If the indexed certificate does not contain a DSA key this method simply
     * returns the public key. If the DSA key already contains DSA parameters
     * the key is also only returned.
     * </p>
     *
     * @param certs The certification path.
     * @param index The index of the certificate which contains the public key
     *              which should be extended with DSA parameters.
     * @return The public key of the certificate in list position
     *         <code>index</code> extended with DSA parameters if applicable.
     * @throws AnnotatedException if DSA parameters cannot be inherited.
     */
    protected static PublicKey getNextWorkingKey(List certs, int index, JcaJceHelper helper)
        throws CertPathValidatorException
    {
        Certificate cert = (Certificate)certs.get(index);
        PublicKey pubKey = cert.getPublicKey();
        if (!(pubKey instanceof DSAPublicKey))
        {
            return pubKey;
        }
        DSAPublicKey dsaPubKey = (DSAPublicKey)pubKey;
        if (dsaPubKey.getParams() != null)
        {
            return dsaPubKey;
        }
        for (int i = index + 1; i < certs.size(); i++)
        {
            X509Certificate parentCert = (X509Certificate)certs.get(i);
            pubKey = parentCert.getPublicKey();
            if (!(pubKey instanceof DSAPublicKey))
            {
                throw new CertPathValidatorException(
                    "DSA parameters cannot be inherited from previous certificate.");
            }
            DSAPublicKey prevDSAPubKey = (DSAPublicKey)pubKey;
            if (prevDSAPubKey.getParams() == null)
            {
                continue;
            }
            DSAParams dsaParams = prevDSAPubKey.getParams();
            DSAPublicKeySpec dsaPubKeySpec = new DSAPublicKeySpec(
                dsaPubKey.getY(), dsaParams.getP(), dsaParams.getQ(), dsaParams.getG());
            try
            {
                KeyFactory keyFactory = helper.createKeyFactory("DSA");
                return keyFactory.generatePublic(dsaPubKeySpec);
            }
            catch (Exception exception)
            {
                throw new RuntimeException(exception.getMessage());
            }
        }
        throw new CertPathValidatorException("DSA parameters cannot be inherited from previous certificate.");
    }

    /**
     * Find the issuer certificates of a given certificate.
     *
     * @param cert       The certificate for which an issuer should be found.
     * @return A <code>Collection</code> object containing the issuer
     *         <code>X509Certificate</code>s. Never <code>null</code>.
     * @throws AnnotatedException if an error occurs.
     */
    static Collection findIssuerCerts(
        X509Certificate cert,
        List<CertStore> certStores,
        List<PKIXCertStore> pkixCertStores)
        throws AnnotatedException
    {
        X509CertSelector selector = new X509CertSelector();

        try
        {
            selector.setSubject(((X509Certificate)cert).getIssuerX500Principal().getEncoded());
        }
        catch (IOException e)
        {
            throw new AnnotatedException(
                           "Subject criteria for certificate selector to find issuer certificate could not be set.", e);
        }

        try
        {
            byte[] akiExtensionValue = cert.getExtensionValue(AUTHORITY_KEY_IDENTIFIER);
            if (akiExtensionValue != null)
            {
                ASN1OctetString aki = ASN1OctetString.getInstance(akiExtensionValue);
                byte[] authorityKeyIdentifier = AuthorityKeyIdentifier.getInstance(aki.getOctets()).getKeyIdentifier();
                if (authorityKeyIdentifier != null)
                {
                    selector.setSubjectKeyIdentifier(new DEROctetString(authorityKeyIdentifier).getEncoded());
                }
            }
        }
        catch (Exception e)
        {
            // authority key identifier could not be retrieved from target cert, just search without it
        }

        PKIXCertStoreSelector certSelect = new PKIXCertStoreSelector.Builder(selector).build();
        Set certs = new LinkedHashSet();

        Iterator iter;

        try
        {
            List matches = new ArrayList();

            matches.addAll(RevocationUtilities.findCertificates(certSelect, certStores));
            matches.addAll(RevocationUtilities.findCertificates(certSelect, pkixCertStores));

            iter = matches.iterator();
        }
        catch (AnnotatedException e)
        {
            throw new AnnotatedException("Issuer certificate cannot be searched.", e);
        }

        X509Certificate issuer = null;
        while (iter.hasNext())
        {
            issuer = (X509Certificate)iter.next();
            // issuer cannot be verified because possible DSA inheritance
            // parameters are missing
            certs.add(issuer);
        }
        return certs;
    }

    protected static void verifyX509Certificate(X509Certificate cert, PublicKey publicKey,
                                                String sigProvider)
        throws GeneralSecurityException
    {
        if (sigProvider == null)
        {
            cert.verify(publicKey);
        }
        else
        {
            cert.verify(publicKey, sigProvider);
        }
    }

    static void checkCRLsNotEmpty(Set crls, Object cert)
        throws CRLNotFoundException
    {
        if (crls.isEmpty())
        {
//            if (cert instanceof X509AttributeCertificate)
//            {
//                X509AttributeCertificate aCert = (X509AttributeCertificate)cert;
//
//                throw new NoCRLFoundException("No CRLs found for issuer \"" + aCert.getIssuer().getPrincipals()[0] + "\"");
//            }
//            else
            {
                X500Name certIssuer = getIssuer((X509Certificate)cert);

                throw new CRLNotFoundException(
                    "No CRLs found for issuer \"" + RFC4519Style.INSTANCE.toString(certIssuer) + "\"");
            }
        }
    }

    public static boolean isIndirectCRL(X509CRL crl)
        throws CRLException
    {
        try
        {
            byte[] idp = crl.getExtensionValue(Extension.issuingDistributionPoint.getId());
            return idp != null
                && IssuingDistributionPoint.getInstance(ASN1OctetString.getInstance(idp).getOctets()).isIndirectCRL();
        }
        catch (Exception e)
        {
            throw new CRLException(
                    "exception reading IssuingDistributionPoint", e);
        }
    }

    private static X500Name getIssuer(X509Certificate cert)
    {
        return getX500Name(cert.getIssuerX500Principal());
    }

    private static X500Name getIssuer(X509CRL crl)
    {
        return getX500Name(crl.getIssuerX500Principal());
    }

    private static X500Name getX500Name(X500Principal principal)
    {
        return X500Name.getInstance(principal.getEncoded());
    }
}
