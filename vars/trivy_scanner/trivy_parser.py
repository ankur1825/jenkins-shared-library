import json
from typing import List, Optional, Tuple
from models import Vulnerability


def _cvss_metrics(vuln: dict) -> Tuple[float, float]:
    cvss = vuln.get("CVSS", {})
    if not isinstance(cvss, dict) or not cvss:
        return 0.0, 0.0

    preferred_sources = ["nvd", "redhat", "ghsa", "amazon"]
    first_entry = None
    for source in preferred_sources:
        if isinstance(cvss.get(source), dict):
            first_entry = cvss[source]
            break

    if first_entry is None:
        first_entry = next((entry for entry in cvss.values() if isinstance(entry, dict)), {})

    return (
        float(first_entry.get("V3Score", first_entry.get("V2Score", 0.0)) or 0.0),
        float(first_entry.get("ExploitabilityScore", 0.0) or 0.0),
    )


def _normalize_severity(value: str) -> str:
    severity = (value or "UNKNOWN").upper()
    return severity if severity in {"CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN"} else "UNKNOWN"


def _line_number(value) -> Optional[int]:
    try:
        return int(value) if value is not None else None
    except (TypeError, ValueError):
        return None


def parse_trivy_output(filepath: str) -> List[Vulnerability]:
    with open(filepath) as f:
        data = json.load(f)

    vulnerabilities = []

    for result in data.get("Results", []):
        target = result.get("Target", "unknown")

        for vuln in result.get("Vulnerabilities", []) or []:
            cvss_score, exploitability_score = _cvss_metrics(vuln)

            vuln_obj = Vulnerability(
                target=target,
                vulnerability_id=vuln.get("VulnerabilityID", "UNKNOWN"),
                package_name=vuln.get("PkgName", "Unknown package"),
                installed_version=vuln.get("InstalledVersion", "N/A"),
                fixed_version=vuln.get("FixedVersion", "N/A"),
                severity=_normalize_severity(vuln.get("Severity")),
                title=vuln.get("Title", ""),
                description=vuln.get("Description", ""),
                cvss_score=cvss_score,
                exploitability_score=exploitability_score,
                source="Trivy",
                rule=vuln.get("VulnerabilityID", "UNKNOWN"),
            )
            vulnerabilities.append(vuln_obj)

        for misconfig in result.get("Misconfigurations", []) or []:
            finding_id = misconfig.get("ID") or misconfig.get("AVDID") or misconfig.get("Type") or "MISCONFIGURATION"
            description_parts = [
                misconfig.get("Title", ""),
                misconfig.get("Message", ""),
                misconfig.get("Description", ""),
            ]
            description = " ".join(part for part in description_parts if part).strip()
            vulnerabilities.append(Vulnerability(
                target=target,
                vulnerability_id=finding_id,
                package_name=misconfig.get("Type", "Configuration"),
                installed_version="N/A",
                fixed_version=misconfig.get("Resolution", "Review and harden this configuration."),
                severity=_normalize_severity(misconfig.get("Severity")),
                title=misconfig.get("Title", "Configuration policy finding"),
                description=description or "Configuration policy finding detected.",
                source="Trivy-Misconfiguration",
                rule=finding_id,
            ))

        for secret in result.get("Secrets", []) or []:
            finding_id = secret.get("RuleID") or secret.get("Category") or "SECRET"
            line = _line_number(secret.get("StartLine") or secret.get("EndLine"))
            vulnerabilities.append(Vulnerability(
                target=target,
                vulnerability_id=finding_id,
                package_name=secret.get("Category", "Secret"),
                installed_version="N/A",
                fixed_version="Remove the secret from source, rotate the credential, and use an approved secret manager.",
                severity=_normalize_severity(secret.get("Severity") or "HIGH"),
                title=secret.get("Title", "Secret detected"),
                description=secret.get("Match", "") or "Potential secret detected in the scanned artifact.",
                source="Trivy-Secret",
                line=line,
                rule=finding_id,
            ))

    print(f"[Parser] Parsed {len(vulnerabilities)} security findings from {filepath}")
    return vulnerabilities
