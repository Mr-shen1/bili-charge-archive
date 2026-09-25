FROM python:3.12.10-slim@sha256:fd95fa221297a88e1cf49c55ec1828edd7c5a428187e67b5d1805692d11588db
WORKDIR /app
COPY bili_monitor ./bili_monitor
USER 65532:65532
CMD ["python", "-m", "bili_monitor"]
