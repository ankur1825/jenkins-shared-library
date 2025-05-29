# main.py
import click
import sys
from scanner import scan_image
from trivy_parser import parse_trivy_output
from api_client import upload_vulnerabilities

@click.command()
@click.option('--image', prompt='Docker image name', help='The docker image to scan.')
@click.option('--upload', is_flag=True, help='Upload results to backend API.')
@click.option('--application', required=True, help='Application name to associate the scan with.')
@click.option('--jenkins-job', required=True, help='Jenkins job name.')
@click.option('--build-number', required=True, type=int, help='Jenkins build number.')
def main(image, upload, application, jenkins_job, build_number):
    print(f"Scanning Docker image: {image}")
    scan_image(image)

    vulnerabilities = parse_trivy_output("trivy-report.json")
    print(f"Found {len(vulnerabilities)} vulnerabilities.")

    for v in vulnerabilities:
        print(f"- {v.severity}: {v.package_name} ({v.vulnerability_id})")

    if upload:
        upload_vulnerabilities(vulnerabilities, application, jenkins_job, build_number)

    # Fail pipeline on CRITICAL or HIGH
    blocking = [v for v in vulnerabilities if v.severity.upper() in {"CRITICAL", "HIGH"}]
    if blocking:
        print(f"Found {len(blocking)} blocking vulnerabilities (CRITICAL or HIGH).")
        sys.exit(1)    

if __name__ == "__main__":
    main()
