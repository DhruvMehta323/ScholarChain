# S3 Vectors: one vector bucket, five contract indexes, one small test index for live suites. Cosine distance, float32.

resource "aws_s3vectors_vector_bucket" "this" {
  vector_bucket_name = var.vector_bucket_name
  force_destroy      = var.vector_force_destroy

  encryption_configuration {
    sse_type = "AES256"
  }

  tags = { Name = var.vector_bucket_name }
}

resource "aws_s3vectors_index" "this" {
  for_each           = toset(concat(var.vector_indexes, [var.vector_test_index_name]))
  vector_bucket_name = aws_s3vectors_vector_bucket.this.vector_bucket_name
  index_name         = each.value
  data_type          = "float32"
  dimension          = var.vector_dimension
  distance_metric    = "cosine"

  encryption_configuration {
    sse_type = "AES256"
  }

  tags = { Name = each.value }
}
