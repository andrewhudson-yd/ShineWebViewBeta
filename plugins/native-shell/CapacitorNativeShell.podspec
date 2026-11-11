require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name = 'CapacitorNativeShell'
  s.version = package['version']
  s.summary = package['description']
  s.license = 'Proprietary'
  s.homepage = 'https://dotlogics.co.uk'
  s.author = 'Dotlogics'
  s.source = { :git => 'https://example.invalid/native-shell.git', :tag => s.version.to_s }
  s.source_files = 'ios/Sources/**/*.{swift,h,m}'
  s.ios.deployment_target = '15.0'
  s.dependency 'Capacitor'
  s.dependency 'OneSignalXCFramework', '>= 5.2.0', '< 6.0'
  s.swift_version = '5.9'
end
