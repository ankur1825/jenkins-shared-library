import json
import sys
import os
from datetime import datetime
#import pytz

input_file = sys.argv[1]
output_file = sys.argv[2]

if not os.path.exists(input_file) or os.path.getsize(input_file) == 0:
    print("[INFO] No issues found or file is empty. Creating empty output.")
    with open(output_file, "w") as f:
        json.dump([], f, indent=2)
    sys.exit(0)

try:
    with open(input_file) as f:
        data = json.load(f)
except json.JSONDecodeError:
    print("[ERROR] Failed to parse JSON. Input file is invalid.")
    sys.exit(1)

sast_keywords = {
    "sql injection": "Critical",
    "xss": "High",
    "cross-site scripting": "High",
    "hardcoded password": "High",
    "command injection": "Critical",
    "insecure deserialization": "Critical",
    "csrf": "Medium",
    "open redirect": "Medium",
    "path traversal": "High",
    "buffer overflow": "Critical",
    "sensitive data exposure": "High"
}

risk_score_map = {
    "Low": 1,
    "Medium": 2,
    "High": 3,
    "Critical": 4
}

# Set Eastern Timezone (New York)
#est = pytz.timezone('America/New_York')
#timestamp = datetime.now(est).strftime('%Y-%m-%d %I:%M:%S %p %Z')

processed = []
fail_pipeline = False

for issue in data.get("issues", []):
    message = issue.get("message", "").lower()
    prediction = "Low"

    for keyword, severity in sast_keywords.items():
        if keyword in message:
            prediction = severity
            break

    if issue.get("severity") in ["CRITICAL", "HIGH"]:
        fail_pipeline = True

    risk_score = risk_score_map.get(prediction, 1)

    processed.append({
        "target": issue.get("component", ""),
        "package_name": "SonarQube Code Scan",
        "installed_version": "N/A",
        "vulnerability_id": issue.get("rule", ""),
        "message": issue.get("message", ""),
        "line": issue.get("line", 0),
        "type": issue.get("type", ""),
        "severity": issue.get("severity", ""),
        "predictedSeverity": prediction,
        "riskScore": risk_score,
        "rule": issue.get("rule", ""),
        "status": issue.get("status", ""),
        "author": issue.get("author", ""),
        "source": "SonarQube",
        "timestamp": datetime.utcnow().isoformat() + "Z"
    })

with open(output_file, "w") as f:
    json.dump(processed, f, indent=2)

if fail_pipeline:
    print("FAIL_PIPELINE=true")
else:
    print("FAIL_PIPELINE=false")    
