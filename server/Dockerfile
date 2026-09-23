FROM golang:1.22-alpine AS build
WORKDIR /src
COPY go.mod go.sum* ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o /out/wakelinkd ./cmd/wakelinkd

FROM gcr.io/distroless/static-debian12
COPY --from=build /out/wakelinkd /wakelinkd
EXPOSE 8080
ENTRYPOINT ["/wakelinkd"]
