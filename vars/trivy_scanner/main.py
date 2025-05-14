# main.py
import click
from scanner import scan_image
from trivy_parser import parse_trivy_output
from api_client import upload_vulnerabilities

@click.command()
@click.option('--image', prompt='Docker image name', help='The docker image to scan.')
@click.option('--upload', is_flag=True, help='Upload results to backend API.')
def main(image, upload):
    print(f"🚀 Scanning Docker image: {image}")
    scan_image(image)

    vulnerabilities = parse_trivy_output("trivy-report.json")
    print(f"🛡️ Found {len(vulnerabilities)} vulnerabilities.")

    for v in vulnerabilities:
        print(f"- {v.severity}: {v.package_name} ({v.vulnerability_id})")

    if upload:
        upload_vulnerabilities(vulnerabilities)

if __name__ == "__main__":
    main()
