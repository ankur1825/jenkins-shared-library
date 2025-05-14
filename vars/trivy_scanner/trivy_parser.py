def parse_trivy_output(filepath: str) -> list:
    import json
    from models import Vulnerability

    with open(filepath) as f:
        data = json.load(f)

    vulnerabilities = []

    for result in data.get("Results", []):
        for vuln in result.get("Vulnerabilities", []):
            cvss = vuln.get("CVSS", {})
            cvss_score = 0.0
            exploitability_score = 0.0

            if isinstance(cvss, dict):
                # Pick first CVSS entry (e.g., "nvd", "redhat", etc.)
                first_entry = next(iter(cvss.values()), {})
                cvss_score = first_entry.get("V3Score", 0.0)
                exploitability_score = first_entry.get("ExploitabilityScore", 0.0)

            vuln_obj = Vulnerability(
                target=result.get("Target", "unknown"),
                vulnerability_id=vuln.get("VulnerabilityID"),
                package_name=vuln.get("PkgName"),
                installed_version=vuln.get("InstalledVersion"),
                fixed_version=vuln.get("FixedVersion", "N/A"),
                severity=vuln.get("Severity"),
                title=vuln.get("Title", ""),
                description=vuln.get("Description", ""),
                cvss_score=cvss_score,
                exploitability_score=exploitability_score, 
            )
            vulnerabilities.append(vuln_obj)

    return vulnerabilities
