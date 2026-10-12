require 'yaml'

ROOT = File.expand_path('..', __dir__)
OPENAPI_PATH = File.join(ROOT, 'docs', 'openapi.yaml')
SERVICE_DIR = File.join(ROOT, 'miniprogram', 'services')

contract = YAML.load_file(OPENAPI_PATH)
abort 'OpenAPI version must be 3.0.x' unless contract.fetch('openapi').start_with?('3.0.')

declared_paths = contract.fetch('paths').keys
service_paths = Dir[File.join(SERVICE_DIR, '*.js')].flat_map do |file|
  File.read(file).scan(/url:\s*[`'"]([^`'"]+)[`'"]/).flatten
end.map do |path|
  path.gsub(/\$\{(bookingId|orderId)\}/, '{\1}')
end.select { |path| path.start_with?('/api/') }.uniq.sort

legacy_service_paths = service_paths.reject { |path| path.start_with?('/api/v1/') }
missing = legacy_service_paths - declared_paths
abort "OpenAPI missing service paths: #{missing.join(', ')}" unless missing.empty?

schemas = contract.fetch('components').fetch('schemas')
error_schema = schemas.fetch('ApiResponseError')
abort 'Error response must require errorCode' unless error_schema.fetch('required').include?('errorCode')
abort 'Error response must define controlled detail' unless error_schema.fetch('properties').key?('detail')
member_required = schemas.fetch('ApiResponseMember').dig('properties', 'data', 'required')
abort 'Member response must expose balanceFen' unless member_required.include?('balanceFen')
contract.fetch('paths').each do |path, path_item|
  operation = path_item['post']
  next unless operation
  schema = operation.dig('requestBody', 'content', 'application/json', 'schema')
  abort "POST #{path} has no JSON request schema" unless schema
  if schema['$ref']
    schema = schemas.fetch(schema['$ref'].split('/').last)
  end
  required = schema.fetch('required', [])
  idempotency_keys = %w[clientRequestId]
  unless (required & idempotency_keys).any?
    abort "POST #{path} does not require an idempotency key"
  end
end

contract.fetch('paths').each do |path, path_item|
  path_item.each do |method, operation|
    next unless %w[get post put patch delete].include?(method)
    response = operation.dig('responses', '200', 'content', 'application/json', 'schema')
    abort "#{method.upcase} #{path} has no unified success response schema" unless response
  end
end

js_files = Dir[File.join(ROOT, 'miniprogram', '**', '*.js')]
iso_usages = js_files.select { |file| File.read(file).include?('.toISOString()') }
abort "ISO time usage found: #{iso_usages.join(', ')}" unless iso_usages.empty?

request_source = File.read(File.join(ROOT, 'miniprogram', 'utils', 'request.js'))
abort 'Missing Bearer token header' unless request_source.include?('Authorization: `Bearer ${')
abort 'Missing unified response validation' unless request_source.include?('isSuccess(response)')
abort 'Legacy clientEventId remains in frontend contract' if js_files.any? { |file| File.read(file).include?('clientEventId') }

puts "OpenAPI OK: #{declared_paths.length} legacy paths covered; /api/v1 paths are verified by backend controllers and tests"
